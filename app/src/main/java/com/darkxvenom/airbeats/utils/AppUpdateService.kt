package com.darkxvenom.airbeats.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import com.darkxvenom.airbeats.R
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Downloads a release APK with visible progress, verifies its signer, then opens Android's installer. */
class AppUpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        promoteToForeground("Preparing update…", 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guarantee foreground state immediately to satisfy Android OS contract
        promoteToForeground("Downloading update", 0)

        val downloadUrl = intent?.getStringExtra(EXTRA_DOWNLOAD_URL).orEmpty()
        if (intent?.action != ACTION_DOWNLOAD || downloadUrl.isBlank()) {
            Timber.w("AppUpdateService started with invalid action or empty URL")
            stopForegroundAndFinish(startId)
            return START_NOT_STICKY
        }

        thread(name = "app-update-download") {
            try {
                downloadAndInstall(downloadUrl)
            } catch (e: Exception) {
                Timber.e(e, "App update download failed")
                showFinishedNotification("Update download failed", "Could not complete update download. Please try again.")
            } finally {
                stopForegroundAndFinish(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, progress: Int) {
        try {
            val notification = notification(title, progress, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to promote AppUpdateService to foreground")
        }
    }

    private fun stopForegroundAndFinish(startId: Int) {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.e(e, "Error stopping foreground service")
        }
        stopSelf(startId)
    }

    private fun downloadAndInstall(downloadUrl: String) {
        val outputDir = File(cacheDir, "updates").apply { mkdirs() }
        val apk = File(outputDir, "AirBeats-update.apk")
        if (apk.exists()) apk.delete()

        var currentUrl = downloadUrl
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            }
            connection.connect()
            val code = connection.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308) && redirects < 5) {
                val newLocation = connection.getHeaderField("Location")
                connection.disconnect()
                if (!newLocation.isNullOrBlank()) {
                    currentUrl = newLocation
                    redirects++
                    continue
                }
            }
            break
        }

        require(connection.responseCode in 200..299) { "Release download failed (${connection.responseCode})" }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            apk.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastProgress = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (total > 0) {
                        val progress = ((downloaded * 100) / total).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            notificationManager.notify(NOTIFICATION_ID, notification("Downloading update", progress, true))
                        }
                    }
                }
            }
        }
        connection.disconnect()
        require(isSignedLikeInstalledApp(apk)) { "Downloaded APK is not signed by this app's signer" }

        showReadyNotification(apk)
        openInstaller(apk)
    }

    @Suppress("DEPRECATION")
    private fun isSignedLikeInstalledApp(apk: File): Boolean {
        if (com.darkxvenom.airbeats.BuildConfig.DEBUG) {
            Timber.d("Debug build: skipping strict signature validation for update")
            return true
        }
        val installed: Array<android.content.pm.Signature>
        val downloaded: Array<android.content.pm.Signature>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners ?: return false
            downloaded = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners ?: return false
        } else {
            installed = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                ?: return false
            downloaded = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?.signatures ?: return false
        }
        return installed.any { current -> downloaded.any { candidate -> current.toByteArray().contentEquals(candidate.toByteArray()) } }
    }

    private fun openInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.provider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    private fun notification(title: String, progress: Int, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.airbeats_monochrome)
            .setContentTitle(title)
            .setContentText(if (ongoing && progress > 0) "$progress%" else null)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress, ongoing)
            .build()

    private fun showReadyNotification(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", apk)
        val contentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                this,
                1,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PendingIntent.getActivity(
                this,
                0,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            "Tap to allow 'Install unknown apps', then install the update."
        } else {
            "Download complete. Tap to install the latest version."
        }

        notificationManager.notify(
            COMPLETED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.airbeats_monochrome)
                .setContentTitle("Update ready to install")
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun showFinishedNotification(title: String, message: String? = null) {
        notificationManager.notify(
            COMPLETED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.airbeats_monochrome)
                .setContentTitle(title)
                .apply {
                    if (!message.isNullOrBlank()) {
                        setContentText(message)
                    }
                }
                .setAutoCancel(true)
                .build()
        )
    }

    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 6_204
        private const val COMPLETED_NOTIFICATION_ID = 6_205
        private const val ACTION_DOWNLOAD = "com.darkxvenom.airbeats.action.DOWNLOAD_UPDATE"
        private const val EXTRA_DOWNLOAD_URL = "download_url"

        fun start(context: Context, downloadUrl: String) {
            val intent = Intent(context, AppUpdateService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
