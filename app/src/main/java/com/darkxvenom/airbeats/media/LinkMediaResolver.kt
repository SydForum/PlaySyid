package com.darkxvenom.airbeats.media

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.darkxvenom.airbeats.constants.AudioQuality
import com.darkxvenom.airbeats.utils.YTPlayerUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class LinkMediaResolver(
    private val context: Context,
    private val tempManager: TemporaryMediaManager = TemporaryMediaManager(context),
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "LinkMediaResolver"
        private const val MAX_CHUNK_BYTES = 12 * 1024 * 1024L // 12MB to capture full 15-30s high-res videos

        private val YT_REGEX = Regex("""(?:youtu\.be/|youtube\.com/(?:embed/|v/|watch\?v=|watch\?.+&v=|shorts/))([\w-]{11})""")
        private val IG_SHORTCODE_REGEX = Regex("""(?:instagram\.com|instagr\.am)/(?:reel|reels|p|share/reel|share/p|tv)/([A-Za-z0-9_-]+)""")
        private val SNAPCHAT_REGEX = Regex("""snapchat\.com/(?:spotlight|add|p|t)/([A-Za-z0-9_-]+)""")
        private val TIKTOK_REGEX = Regex("""(?:tiktok\.com/|vm\.tiktok\.com/|vt\.tiktok\.com/)""")
    }

    suspend fun resolveMedia(url: String): File? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        Log.d(TAG, "Attempting to resolve media from URL: $cleanUrl")

        // 1. YouTube & YouTube Shorts
        val ytVideoId = extractYouTubeVideoId(cleanUrl)
        if (ytVideoId != null) {
            Log.d(TAG, "Identified YouTube video ID: $ytVideoId")
            resolveYouTubeAudio(ytVideoId)?.let { return@withContext it }
        }

        // 2. Instagram Reels & Posts
        if (cleanUrl.contains("instagram.com") || cleanUrl.contains("instagr.am")) {
            Log.d(TAG, "Identified Instagram URL: $cleanUrl")
            resolveInstagramMedia(cleanUrl)?.let { return@withContext it }
        }

        // 3. Snapchat Spotlight & Stories
        if (cleanUrl.contains("snapchat.com") || cleanUrl.contains("snap.com")) {
            Log.d(TAG, "Identified Snapchat URL: $cleanUrl")
            resolveSnapchatMedia(cleanUrl)?.let { return@withContext it }
        }

        // 4. TikTok
        if (TIKTOK_REGEX.containsMatchIn(cleanUrl)) {
            Log.d(TAG, "Identified TikTok URL: $cleanUrl")
            resolveTikTokMedia(cleanUrl)?.let { return@withContext it }
        }

        // 5. Direct Media Links (.mp4, .mp3, .wav, .m4a, .webm, etc.)
        if (isDirectMediaUrl(cleanUrl)) {
            Log.d(TAG, "Identified direct media link: $cleanUrl")
            val ext = cleanUrl.substringAfterLast('.', "mp4").substringBefore('?')
            val targetFile = tempManager.createTempFile(ext)
            if (downloadMediaChunk(cleanUrl, targetFile)) {
                return@withContext targetFile
            }
        }

        // 6. Generic web page with OpenGraph / HTML5 media tags
        resolveGenericWebMedia(cleanUrl)?.let { return@withContext it }

        Log.w(TAG, "Could not resolve audio or video stream from link: $cleanUrl")
        null
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val match = YT_REGEX.find(url)
        return match?.groupValues?.getOrNull(1)
    }

    private suspend fun resolveYouTubeAudio(videoId: String): File? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val playbackData = YTPlayerUtils.playerResponseForPlayback(
                videoId = videoId,
                audioQuality = AudioQuality.LOW,
                connectivityManager = cm
            ).getOrNull() ?: return null

            val streamUrl = playbackData.streamUrl
            if (streamUrl.isBlank()) return null

            val format = playbackData.format
            val ext = if (format.mimeType.contains("webm") || format.mimeType.contains("opus")) "webm" else "m4a"
            val targetFile = tempManager.createTempFile(ext)

            // Append range to avoid throttling from YouTube CDN
            val downloadUrl = if (streamUrl.contains("range=")) streamUrl else "${streamUrl}&range=0-2097151"
            if (downloadMediaChunk(downloadUrl, targetFile)) {
                targetFile
            } else {
                tempManager.cleanup(targetFile)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve YouTube audio for videoId: $videoId", e)
            null
        }
    }

    private suspend fun resolveInstagramMedia(url: String): File? {
        val cleanUrl = url.trim()
        var shortcode = IG_SHORTCODE_REGEX.find(cleanUrl)?.groupValues?.getOrNull(1)

        if (shortcode.isNullOrBlank()) {
            val canonicalUrl = resolveCanonicalUrl(cleanUrl)
            shortcode = IG_SHORTCODE_REGEX.find(canonicalUrl)?.groupValues?.getOrNull(1)
        }

        // Strategy A: Headless Android WebView resolving the embed page
        if (!shortcode.isNullOrBlank()) {
            try {
                val mediaUrl = resolveInstagramViaWebView(cleanUrl, shortcode)
                if (!mediaUrl.isNullOrBlank()) {
                    Log.d(TAG, "Instagram WebView resolved media URL: $mediaUrl")
                    val targetFile = tempManager.createTempFile("mp4")
                    if (downloadMediaChunk(mediaUrl, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Instagram WebView resolution failed for shortcode: $shortcode", e)
            }
        }

        // Strategy B: Check Instagram embed captioned page via direct HTTP
        if (!shortcode.isNullOrBlank()) {
            try {
                val embedUrl = if (cleanUrl.contains("/p/") || cleanUrl.contains("/share/p/")) {
                    "https://www.instagram.com/p/$shortcode/embed/captioned/"
                } else {
                    "https://www.instagram.com/reel/$shortcode/embed/captioned/"
                }
                val request = Request.Builder()
                    .url(embedUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        val mediaUrl = extractMediaUrlFromHtml(html)
                        if (!mediaUrl.isNullOrBlank()) {
                            val targetFile = tempManager.createTempFile("mp4")
                            if (downloadMediaChunk(mediaUrl, targetFile)) {
                                return targetFile
                            }
                            tempManager.cleanup(targetFile)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Instagram embed resolution failed for shortcode: $shortcode", e)
            }
        }

        // Strategy C: Bot User-Agent OpenGraph scrape
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .addHeader("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    val mediaUrl = extractMediaUrlFromHtml(html)
                    if (!mediaUrl.isNullOrBlank()) {
                        val targetFile = tempManager.createTempFile("mp4")
                        if (downloadMediaChunk(mediaUrl, targetFile)) {
                            return targetFile
                        }
                        tempManager.cleanup(targetFile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instagram OpenGraph resolution failed", e)
        }

        return null
    }

    private suspend fun resolveInstagramViaWebView(originalUrl: String, shortcode: String): String? = withContext(Dispatchers.Main) {
        val deferredMediaUrl = CompletableDeferred<String?>()
        var webView: WebView? = null
        try {
            webView = WebView(context.applicationContext)
            webView.layout(0, 0, 1080, 1920)

            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                try {
                    setAcceptThirdPartyCookies(webView, true)
                } catch (_: Exception) {}
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString()
                    if (reqUrl != null && isInstagramMediaUrl(reqUrl)) {
                        Log.d(TAG, "WebView intercepted Instagram media URL: $reqUrl")
                        val cleanMediaUrl = reqUrl
                            .replace(Regex("""[?&]bytestart=\d+"""), "")
                            .replace(Regex("""[?&]byteend=\d+"""), "")
                        if (!deferredMediaUrl.isCompleted) {
                            deferredMediaUrl.complete(cleanMediaUrl)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    val js = """
                        (function() {
                            var v = document.querySelector('video');
                            if (v && (v.currentSrc || v.src)) {
                                return v.currentSrc || v.src;
                            }
                            var s = document.querySelector('video source');
                            if (s && s.src) {
                                return s.src;
                            }
                            var btn = document.querySelector('div[role="button"]') || 
                                      document.querySelector('.PlayButton') || 
                                      document.querySelector('.EmbeddedMediaImage');
                            if (btn) { btn.click(); }
                            if (v) {
                                try { v.play(); } catch(e){}
                                return v.currentSrc || v.src || null;
                            }
                            return null;
                        })()
                    """.trimIndent()
                    view?.evaluateJavascript(js) { result ->
                        val clean = result?.trim('"', ' ', '\'', '\\')
                        if (!clean.isNullOrBlank() && clean != "null" && clean.startsWith("http")) {
                            if (!deferredMediaUrl.isCompleted) {
                                deferredMediaUrl.complete(clean)
                            }
                        }
                    }
                }
            }

            val embedUrl = if (originalUrl.contains("/p/") || originalUrl.contains("/share/p/")) {
                "https://www.instagram.com/p/$shortcode/embed/captioned/"
            } else {
                "https://www.instagram.com/reel/$shortcode/embed/captioned/"
            }

            Log.d(TAG, "Loading Instagram embed URL in WebView: $embedUrl")
            webView.loadUrl(embedUrl)

            val maxWaitMs = 7000L
            val startTime = System.currentTimeMillis()
            while (!deferredMediaUrl.isCompleted && System.currentTimeMillis() - startTime < maxWaitMs) {
                delay(400)
                if (deferredMediaUrl.isCompleted) break
                webView.evaluateJavascript("""
                    (function() {
                        var v = document.querySelector('video');
                        if (v && (v.currentSrc || v.src)) return v.currentSrc || v.src;
                        var s = document.querySelector('video source');
                        if (s && s.src) return s.src;
                        return null;
                    })()
                """.trimIndent()) { result ->
                    val clean = result?.trim('"', ' ', '\'', '\\')
                    if (!clean.isNullOrBlank() && clean != "null" && clean.startsWith("http")) {
                        if (!deferredMediaUrl.isCompleted) {
                            deferredMediaUrl.complete(clean)
                        }
                    }
                }
            }

            if (deferredMediaUrl.isCompleted) deferredMediaUrl.getCompleted() else null
        } catch (e: Exception) {
            Log.e(TAG, "WebView error while resolving Instagram media", e)
            null
        } finally {
            try {
                webView?.stopLoading()
                webView?.webViewClient = WebViewClient()
                webView?.destroy()
            } catch (_: Exception) {}
        }
    }

    private fun isInstagramMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
            lower.contains(".webp") || lower.contains("rsrc.php") || lower.contains(".js") ||
            lower.contains(".css") || lower.contains("/v/t51.")
        ) {
            return false
        }
        return (lower.contains("cdninstagram.com") || lower.contains("fbcdn.net")) &&
                (lower.contains(".mp4") || lower.contains("/v/t50.") || lower.contains("mime_type=video") || lower.contains("bytestart="))
    }

    private fun resolveCanonicalUrl(rawUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(rawUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (_: Exception) {
            rawUrl
        }
    }

    private suspend fun resolveSnapchatMedia(url: String): File? {
        try {
            var currentUrl = url
            val mobileUa = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.80 Mobile Safari/537.36"
            val request = Request.Builder()
                .url(currentUrl)
                .addHeader("User-Agent", mobileUa)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            var html = ""
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    html = response.body?.string().orEmpty()
                }
            }

            // Check if there is a canonical or og:url redirect (e.g. t.snapchat.com short links)
            val canonicalRegex = Regex("""<link\s+[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val ogUrlRegex = Regex("""<meta\s+[^>]*property=["']og:url["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val canonicalUrl = canonicalRegex.find(html)?.groupValues?.get(1)
                ?: ogUrlRegex.find(html)?.groupValues?.get(1)

            if (!canonicalUrl.isNullOrBlank() && canonicalUrl != currentUrl && (canonicalUrl.contains("snapchat.com") || canonicalUrl.contains("snap.com"))) {
                Log.d(TAG, "Following Snapchat canonical URL: $canonicalUrl")
                val redirectReq = Request.Builder()
                    .url(canonicalUrl)
                    .addHeader("User-Agent", mobileUa)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()
                okHttpClient.newCall(redirectReq).execute().use { response ->
                    if (response.isSuccessful) {
                        html = response.body?.string().orEmpty()
                    }
                }
            }

            val mediaUrl = extractSnapchatMediaUrl(html)
            if (!mediaUrl.isNullOrBlank()) {
                Log.d(TAG, "Resolved Snapchat media URL: $mediaUrl")
                val targetFile = tempManager.createTempFile("mp4")
                if (downloadMediaChunk(mediaUrl, targetFile)) {
                    return targetFile
                }
                tempManager.cleanup(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Snapchat media for URL: $url", e)
        }
        return null
    }

    private fun extractSnapchatMediaUrl(html: String): String? {
        fun isValidSnapchatVideo(u: String): Boolean {
            val lower = u.lowercase()
            if (lower.contains("largethumbnail") || lower.contains("thumbnail") ||
                lower.contains("_fmjpeg") || lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".png") || lower.contains(".webp") || lower.contains(".256.") ||
                lower.contains(".1400.") || lower.contains("snapcode") || lower.contains("profilepicture")
            ) {
                return false
            }
            return lower.contains(".27.") || lower.contains("/d/") || lower.contains("/u/") ||
                    lower.contains("/c/") || lower.contains("/h/") || lower.contains("/x/") ||
                    lower.contains(".mp4") || lower.contains("mo=")
        }

        // 1. Search in __NEXT_DATA__
        val nextDataRegex = Regex("""<script\s+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val nextDataMatch = nextDataRegex.find(html)
        if (nextDataMatch != null) {
            val jsonStr = nextDataMatch.groupValues[1]
            val keys = listOf("contentUrl", "mediaUrl")
            for (key in keys) {
                val keyRegex = Regex(""""$key"\s*:\s*"([^"]+)"""")
                for (match in keyRegex.findAll(jsonStr)) {
                    val cand = unescapeJsonString(match.groupValues[1])
                    if (isValidSnapchatVideo(cand)) {
                        return cand
                    }
                }
            }
        }

        // 2. Search whole HTML for Snapchat CDN video URLs (cf-st.sc-cdn.net or bolt-gcdn.sc-cdn.net)
        val cdnRegex = Regex("""https://[a-zA-Z0-9.-]*(?:sc-cdn\.net|bolt-gcdn\.sc-cdn\.net)[^"'\s\\]+""")
        for (match in cdnRegex.findAll(html)) {
            val cand = unescapeJsonString(match.value)
            if (isValidSnapchatVideo(cand)) {
                return cand
            }
        }

        // 3. Fallback to OpenGraph / HTML5 video tags
        return extractMediaUrlFromHtml(html)
    }

    private suspend fun resolveTikTokMedia(url: String): File? {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()

                // TikTok music soundtrack MP3 URL
                val musicRegex = Regex(""""playUrl":\s*"([^"]+)"""")
                val musicMatch = musicRegex.find(html)
                if (musicMatch != null) {
                    val audioUrl = unescapeJsonString(musicMatch.groupValues[1])
                    val targetFile = tempManager.createTempFile("mp3")
                    if (downloadMediaChunk(audioUrl, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                }

                // Video MP4 URL
                val videoRegex = Regex(""""playAddr":\s*"([^"]+)"""")
                val videoMatch = videoRegex.find(html)
                if (videoMatch != null) {
                    val videoUrl = unescapeJsonString(videoMatch.groupValues[1])
                    val targetFile = tempManager.createTempFile("mp4")
                    if (downloadMediaChunk(videoUrl, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                }

                val ogUrl = extractMediaUrlFromHtml(html)
                if (!ogUrl.isNullOrBlank()) {
                    val targetFile = tempManager.createTempFile("mp4")
                    if (downloadMediaChunk(ogUrl, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve TikTok media for URL: $url", e)
        }
        return null
    }

    private suspend fun resolveGenericWebMedia(url: String): File? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("audio/") || contentType.startsWith("video/")) {
                    val ext = if (contentType.contains("audio")) "mp3" else "mp4"
                    val targetFile = tempManager.createTempFile(ext)
                    if (downloadMediaChunk(url, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                    return null
                }

                val html = response.body?.string().orEmpty()
                val mediaUrl = extractMediaUrlFromHtml(html)
                if (!mediaUrl.isNullOrBlank()) {
                    val targetFile = tempManager.createTempFile("mp4")
                    if (downloadMediaChunk(mediaUrl, targetFile)) {
                        return targetFile
                    }
                    tempManager.cleanup(targetFile)
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed generic web media resolution for $url", e)
            null
        }
    }

    private fun extractMediaUrlFromHtml(html: String): String? {
        try {
            val doc = Jsoup.parse(html)
            // 1. og:video / og:video:secure_url
            val ogVideo = doc.select("meta[property=og:video:secure_url]").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[property=og:video]").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[name=twitter:player:stream]").attr("content").takeIf { it.isNotBlank() }

            if (!ogVideo.isNullOrBlank()) {
                return unescapeJsonString(ogVideo)
            }

            // 2. <video src="...">
            val videoSrc = doc.select("video source").attr("src").takeIf { it.isNotBlank() }
                ?: doc.select("video").attr("src").takeIf { it.isNotBlank() }

            if (!videoSrc.isNullOrBlank()) {
                return unescapeJsonString(videoSrc)
            }

            // 3. Regex for JSON / embedded video_url
            val jsonVideoRegex = Regex("""(?:video_url|videoUrl)["']?\s*:\s*["'](https:[^"'\\]+?)["']""")
            val jsonMatch = jsonVideoRegex.find(html)
            if (jsonMatch != null) {
                return unescapeJsonString(jsonMatch.groupValues[1])
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML parsing error", e)
        }
        return null
    }

    private suspend fun downloadMediaChunk(
        mediaUrl: String,
        targetFile: File,
        maxBytes: Long = MAX_CHUNK_BYTES
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(mediaUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .addHeader("Range", "bytes=0-${maxBytes - 1}")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@use downloadWithoutRange(mediaUrl, targetFile, maxBytes)
                }
                val body = response.body ?: return@use false
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (totalRead >= maxBytes) break
                        }
                    }
                }
                targetFile.exists() && targetFile.length() > 2048
            }
        } catch (e: Exception) {
            Log.w(TAG, "Range download failed, attempting direct download", e)
            downloadWithoutRange(mediaUrl, targetFile, maxBytes)
        }
    }

    private fun downloadWithoutRange(
        mediaUrl: String,
        targetFile: File,
        maxBytes: Long
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(mediaUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (totalRead >= maxBytes) break
                        }
                    }
                }
                targetFile.exists() && targetFile.length() > 2048
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct download without range failed", e)
            false
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                lower.endsWith(".wav") || lower.endsWith(".webm") || lower.endsWith(".ogg") ||
                lower.endsWith(".aac") || lower.endsWith(".flac")
    }

    private fun unescapeJsonString(str: String): String {
        return str
            .replace(Regex("""\\u0026""", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("""\\u002f""", RegexOption.IGNORE_CASE), "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\", "")
            .trim()
    }
}
