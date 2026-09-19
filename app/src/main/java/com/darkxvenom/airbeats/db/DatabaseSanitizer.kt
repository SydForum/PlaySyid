package com.darkxvenom.airbeats.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

object DatabaseSanitizer {
    private const val TAG = "DatabaseSanitizer"

    fun sanitizeDatabase(database: MusicDatabase) {
        database.query {
            try {
                val db = openHelper.writableDatabase

                // Clean up any zero or negative playTime events
                db.execSQL("DELETE FROM event WHERE playTime <= 0")

                // Find songs where event count is suspicious and average event playTime is < 25 seconds,
                // or where eventCount is drastically higher than actual play count (totalPlayTime / duration)
                val cursor = db.query(
                    """
                    SELECT e.songId, COUNT(e.rowId) AS eventCount, SUM(e.playTime) AS totalTime, s.duration
                    FROM event e
                    LEFT JOIN song s ON s.id = e.songId
                    GROUP BY e.songId
                    HAVING eventCount > 3 AND (
                        (totalTime * 1.0 / eventCount) < 25000 
                        OR (s.duration > 0 AND eventCount > CAST((totalTime * 1.0 / (s.duration * 1000.0) * 1.5) AS INTEGER))
                    )
                    """.trimIndent()
                )

                val songsToHeal = mutableListOf<HealCandidate>()
                cursor.use { c ->
                    val idIdx = c.getColumnIndex("songId")
                    val countIdx = c.getColumnIndex("eventCount")
                    val timeIdx = c.getColumnIndex("totalTime")
                    val durIdx = c.getColumnIndex("duration")
                    while (c.moveToNext()) {
                        val songId = c.getString(idIdx)
                        val eventCount = c.getInt(countIdx)
                        val totalTime = c.getLong(timeIdx)
                        val duration = if (durIdx != -1 && !c.isNull(durIdx)) c.getInt(durIdx) else -1
                        songsToHeal.add(HealCandidate(songId, eventCount, totalTime, duration))
                    }
                }

                if (songsToHeal.isEmpty()) {
                    Timber.tag(TAG).d("Database playback stats are clean. No healing needed.")
                    return@query
                }

                Timber.tag(TAG).i("Detected ${songsToHeal.size} songs with fragmented playback stats. Starting healing...")

                db.beginTransaction()
                try {
                    for (candidate in songsToHeal) {
                        healSongEvents(db, candidate)
                    }
                    db.setTransactionSuccessful()
                    Timber.tag(TAG).i("Successfully sanitized and consolidated playback events for ${songsToHeal.size} songs.")
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error sanitizing database events")
            }
        }
    }

    private data class HealCandidate(
        val songId: String,
        val eventCount: Int,
        val totalPlayTime: Long,
        val duration: Int,
    )

    private fun healSongEvents(db: SupportSQLiteDatabase, candidate: HealCandidate) {
        val songDurationMs = if (candidate.duration > 0) {
            candidate.duration * 1000L
        } else {
            180_000L // default 3 minutes fallback
        }

        // Fetch all events for this song ordered by rowId ASC
        val eventCursor = db.query(
            "SELECT rowId, timestamp, playTime FROM event WHERE songId = ? ORDER BY rowId ASC",
            arrayOf(candidate.songId)
        )

        data class RawEvent(val rowId: Long, val timestamp: String, val playTime: Long)
        val rawEvents = mutableListOf<RawEvent>()
        eventCursor.use { c ->
            while (c.moveToNext()) {
                rawEvents.add(RawEvent(c.getLong(0), c.getString(1), c.getLong(2)))
            }
        }

        if (rawEvents.isEmpty()) return

        // Consolidate adjacent fragmented events into real play sessions bounded by song duration
        val consolidatedEvents = mutableListOf<Pair<String, Long>>()
        var currentSessionTimestamp = rawEvents.first().timestamp
        var currentSessionTimeMs = 0L

        for (evt in rawEvents) {
            if (currentSessionTimeMs == 0L) {
                currentSessionTimestamp = evt.timestamp
            }
            currentSessionTimeMs += evt.playTime

            // When accumulated time reaches or exceeds song duration, finalize one play
            if (currentSessionTimeMs >= songDurationMs) {
                consolidatedEvents.add(currentSessionTimestamp to currentSessionTimeMs)
                currentSessionTimeMs = 0L
            }
        }

        // Add remaining play time if it represents a meaningful listen (e.g. >= 20s or only event)
        if (currentSessionTimeMs >= 20000L || consolidatedEvents.isEmpty()) {
            consolidatedEvents.add(currentSessionTimestamp to currentSessionTimeMs)
        } else if (consolidatedEvents.isNotEmpty()) {
            // Append small trailing remnant to the last play session
            val lastIdx = consolidatedEvents.size - 1
            val (lastTs, lastTime) = consolidatedEvents[lastIdx]
            consolidatedEvents[lastIdx] = lastTs to (lastTime + currentSessionTimeMs)
        }

        // Delete old fragmented events for this song
        db.delete("event", "songId = ?", arrayOf(candidate.songId))

        // Insert consolidated events
        for ((timestamp, playTime) in consolidatedEvents) {
            val cv = ContentValues().apply {
                put("songId", candidate.songId)
                put("timestamp", timestamp)
                put("playTime", playTime)
            }
            db.insert("event", SQLiteDatabase.CONFLICT_REPLACE, cv)
        }

        // Ensure song's totalPlayTime matches totalPlayTime
        val updateCv = ContentValues().apply {
            put("totalPlayTime", candidate.totalPlayTime)
        }
        db.update("song", SQLiteDatabase.CONFLICT_IGNORE, updateCv, "id = ?", arrayOf(candidate.songId))
    }
}
