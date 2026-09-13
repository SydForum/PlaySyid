package com.darkxvenom.airbeats.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class CloudBackupClient {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    // Create a safe, normalized folder name from the email
    private fun getEmailFolder(email: String): String {
        return email.trim().lowercase().replace("@", "_at_").replace(".", "_dot_")
    }

    suspend fun checkBackupExists(email: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                val fileName = "airbeats/backups/$folder/airbeats_backup.backup"
                val request =
                    Request
                        .Builder()
                        .url("$BASE_URL/download?file=$fileName&_t=${System.currentTimeMillis()}")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("X-API-Key", API_KEY)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    val exists = response.isSuccessful && (response.body?.contentLength() ?: 0L) != 0L
                    Timber.tag("CloudBackup").d("checkBackupExists for $email (folder=$folder): exists=$exists, code=${response.code}")
                    exists
                }
            }.onFailure {
                Timber.tag("CloudBackup").e(it, "checkBackupExists error for $email")
            }.getOrDefault(false)
        }

    suspend fun uploadBackup(email: String, name: String, backupFile: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                Timber.tag("CloudBackup").d("uploadBackup for $email (size=${backupFile.length()} bytes)")
                
                // 1. Upload the details.json
                val detailsJson = JSONObject()
                    .put("email", email.trim().lowercase())
                    .put("name", name)
                    .put("backupFile", "airbeats_backup.backup")
                    .put("backupSize", backupFile.length())
                    .put("lastBackupAt", System.currentTimeMillis())

                val detailsRequest = Request.Builder()
                    .url("$BASE_URL/write?file=airbeats/backups/$folder/details.json")
                    .addHeader("X-API-Key", API_KEY)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .post(detailsJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                
                val detailsSuccess = client.newCall(detailsRequest).execute().use { it.isSuccessful }
                if (!detailsSuccess) {
                    Timber.tag("CloudBackup").e("uploadBackup: failed to write details.json")
                    return@runCatching false
                }

                // 2. Upload the actual backup zip file
                val backupRequest = Request.Builder()
                    .url("$BASE_URL/upload?file=airbeats/backups/$folder/airbeats_backup.backup")
                    .addHeader("X-API-Key", API_KEY)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .post(backupFile.asRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val backupSuccess = client.newCall(backupRequest).execute().use { it.isSuccessful }
                Timber.tag("CloudBackup").d("uploadBackup: backup upload success=$backupSuccess")
                backupSuccess
            }.onFailure {
                Timber.tag("CloudBackup").e(it, "uploadBackup failed for $email")
            }.getOrDefault(false)
        }

    suspend fun downloadBackup(email: String, destFile: File): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                val fileName = "airbeats/backups/$folder/airbeats_backup.backup"
                Timber.tag("CloudBackup").d("downloadBackup: starting download from $fileName to ${destFile.absolutePath}")

                val request = Request.Builder()
                    .url("$BASE_URL/download?file=$fileName&_t=${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("X-API-Key", API_KEY)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        Timber.tag("CloudBackup").e("downloadBackup failed: HTTP ${response.code}: $errBody")
                        error("HTTP ${response.code}: $errBody")
                    }
                    
                    val body = response.body ?: error("Empty response body from server")
                    destFile.parentFile?.mkdirs()
                    if (destFile.exists()) destFile.delete()

                    FileOutputStream(destFile).use { fos ->
                        body.byteStream().use { stream ->
                            val bytesCopied = stream.copyTo(fos)
                            Timber.tag("CloudBackup").d("downloadBackup: successfully saved $bytesCopied bytes to ${destFile.absolutePath}")
                            if (bytesCopied == 0L) {
                                error("Downloaded backup file is 0 bytes")
                            }
                        }
                    }
                    true
                }
            }.onFailure {
                Timber.tag("CloudBackup").e(it, "downloadBackup failed for $email")
            }
        }

    private companion object {
        val BASE_URL = com.darkxvenom.airbeats.BuildConfig.STATS_BASE_URL
        val API_KEY = com.darkxvenom.airbeats.BuildConfig.STATS_API_KEY
    }
}
