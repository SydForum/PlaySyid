package com.darkxvenom.airbeats.utils

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.File

class AirBeatsBackupAgent : BackupAgent() {

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        // Key/value backup pass (not utilized, full data backup handles comprehensive state)
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) {
        // Key/value restore pass
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        Timber.i("AirBeatsBackupAgent: Android OS onFullBackup triggered")
        runCatching {
            val backupFile = AutoBackupManager.getAutoBackupFile(this)
            // Ensure Room DB and preferences are flushed to local backup file
            AutoBackupManager.createAutoBackup(this, null, notifyBackupManager = false)
            if (backupFile.exists() && backupFile.length() > 0) {
                fullBackupFile(backupFile, data)
                Timber.i("AirBeatsBackupAgent: Android OS onFullBackup completed successfully (${backupFile.length()} bytes)")
            } else {
                Timber.w("AirBeatsBackupAgent: Backup file is empty, skipping OS full backup")
            }
        }.onFailure { e ->
            Timber.e(e, "AirBeatsBackupAgent: onFullBackup failed")
        }
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor?,
        size: Long,
        destination: File?,
        type: Int,
        mode: Long,
        mtime: Long
    ) {
        Timber.i("AirBeatsBackupAgent: Android OS onRestoreFile called for ${destination?.absolutePath} (size=$size)")
        super.onRestoreFile(data, size, destination, type, mode, mtime)
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        Timber.i("AirBeatsBackupAgent: Android OS onRestoreFinished. Unpacking restored state...")
        runCatching {
            AutoBackupManager.checkAndRestoreOnOpen(this)
        }.onFailure { e ->
            Timber.e(e, "AirBeatsBackupAgent: Failed to unpack restored backup in onRestoreFinished")
        }
    }
}
