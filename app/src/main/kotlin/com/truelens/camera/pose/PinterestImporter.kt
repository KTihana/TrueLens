package com.truelens.camera.pose

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PinterestBoardSummary(
    val id: String,
    val name: String,
    val pinCount: Int,
    val isSecret: Boolean
)

data class ImportedPinData(
    val title: String,
    val imageUrl: String
)

/**
 * Supports BOTH:
 * 1) Zero-login Public Pinterest Board URL import (RSS feed + high-res pinimg upgrade + single Pin fallback)
 * 2) Official Pinterest API v5 OAuth 2.0 / Access Token sync for Private & Secret Boards
 */
class PinterestImporter(context: Context) {

    private val prefs = context.getSharedPreferences("truelens_pinterest", Context.MODE_PRIVATE)

    fun getSavedAccessToken(): String? = prefs.getString("access_token", null)?.ifBlank { null }
    fun getSavedClientId(): String = prefs.getString("client_id", "") ?: ""

    fun saveCredentials(clientId: String, accessToken: String?) {
        prefs.edit()
            .putString("client_id", clientId.trim())
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    putString("access_token", accessToken.trim())
                }
            }
            .apply()
    }

    fun clearToken() {
        prefs.edit().remove("access_token").apply()
    }

    fun buildOAuthAuthorizeUrl(clientId: String): String {
        val redirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val scopes = URLEncoder.encode("boards:read,pins:read,boards:read_secret,pins:read_secret", "UTF-8")
        return "https://www.pinterest.com/oauth/?client_id=${clientId.trim()}&redirect_uri=$redirect&response_type=code&scope=$scopes"
    }

    suspend fun exchangeOAuthCode(
        clientId: String,
        clientSecret: String,
        code: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.pinterest.com/v5/oauth/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basic")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}"
            conn.outputStream.use { it.write(body.toByteArray()) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                return@withContext Result.failure(Exception("Pinterest OAuth error ($status): $text"))
            }
            val token = JSONObject(text).getString("access_token")
            saveCredentials(clientId, token)
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAuthenticatedBoards(): Result<List<PinterestBoardSummary>> = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
            ?: return@withContext Result.failure(Exception("Not connected to Pinterest"))
        try {
            val text = httpGetText(
                "https://api.pinterest.com/v5/boards?page_size=50&privacy=ALL",
                authToken = token
            )
            val items = JSONObject(text).optJSONArray("items")
            val boards = mutableListOf<PinterestBoardSummary>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val b = items.getJSONObject(i)
                    boards.add(
                        PinterestBoardSummary(
                            id = b.getString("id"),
                            name = b.optString("name", "Board"),
                            pinCount = b.optInt("pin_count", 0),
                            isSecret = b.optString("privacy", "PUBLIC").equals("SECRET", ignoreCase = true)
                        )
                    )
                }
            }
            Result.success(boards)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBoardPinsViaApi(boardId: String): Result<List<ImportedPinData>> = withContext(Dispatchers.IO) {
        val token = getSavedAccessToken()
            ?: return@withContext Result.failure(Exception("Not connected to Pinterest"))
        try {
            val text = httpGetText(
                "https://api.pinterest.com/v5/boards/$boardId/pins?page_size=40",
                authToken = token
            )
            val items = JSONObject(text).optJSONArray("items")
            val pins = mutableListOf<ImportedPinData>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val p = items.getJSONObject(i)
                    val title = p.optString("title", "")
                    val images = p.optJSONObject("media")?.optJSONObject("images")
                    val url = images?.optJSONObject("1200x")?.optString("url")
                        ?: images?.optJSONObject("600x")?.optString("url")
                        ?: images?.optJSONObject("400x300")?.optString("url")
                    if (!url.isNullOrBlank()) {
                        pins.add(ImportedPinData(title = title, imageUrl = url))
                    }
                }
            }
            Result.success(pins)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Zero-login Public Pinterest Board or single Pin URL importer.
     * Supports:
     *  - https://www.pinterest.com/username/board-name/
     *  - https://pin.it/xxxx (follows redirects automatically)
     *  - Direct image URLs
     */
    suspend fun fetchPublicPinterestUrl(rawUrl: String): Result<Pair<String, List<ImportedPinData>>> =
        withContext(Dispatchers.IO) {
            try {
                val cleaned = extractFirstUrl(rawUrl)
                    ?: return@withContext Result.failure(Exception("No valid URL found"))

                // Direct image URL check
                if (cleaned.endsWith(".jpg", true) || cleaned.endsWith(".jpeg", true) ||
                    cleaned.endsWith(".png", true) || cleaned.endsWith(".webp", true)
                ) {
                    return@withContext Result.success(
                        "Imported Pose" to listOf(ImportedPinData("Imported Pose", cleaned))
                    )
                }

                // Follow shortlink redirects (e.g. pin.it -> pinterest.com)
                val resolved = resolveRedirects(cleaned)
                val uri = Uri.parse(resolved)
                val segments = uri.pathSegments.filter { it.isNotBlank() }

                // Case 1: Public board URL (pinterest.com/<user>/<board>) -> fetch .rss
                if (uri.host?.contains("pinterest", ignoreCase = true) == true &&
                    segments.size >= 2 && segments[0] != "pin"
                ) {
                    val boardSlug = segments[1].replace("-", " ").replaceFirstChar { it.uppercase() }
                    val rssUrl = "https://www.pinterest.com/${segments[0]}/${segments[1]}.rss"
                    val rssText = runCatching { httpGetText(rssUrl) }.getOrNull()
                    if (!rssText.isNullOrBlank()) {
                        val pins = parsePinterestRss(rssText)
                        if (pins.isNotEmpty()) {
                            return@withContext Result.success(boardSlug to pins)
                        }
                    }
                }

                // Case 2: Single pin or fallback HTML scrape for high-res i.pinimg.com / og:image
                val html = httpGetText(resolved)
                val pins = extractPinImagesFromHtml(html)
                if (pins.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("Could not find public pins at that link. If this is a Secret board, connect your Pinterest account below.")
                    )
                }
                val title = segments.lastOrNull()?.replace("-", " ")?.ifBlank { "Pinterest Import" }
                    ?: "Pinterest Import"
                Result.success(title to pins)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun downloadImageBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePinterestRss(rss: String): List<ImportedPinData> {
        val result = mutableListOf<ImportedPinData>()
        val seen = mutableSetOf<String>()
        val imgRegex = Regex("""https://i\.pinimg\.com/[a-zA-Z0-9_]+/[a-zA-Z0-9/_-]+\.(?:jpg|jpeg|png|webp)""")
        imgRegex.findAll(rss).forEach { match ->
            val rawImg = match.value
            val upgraded = upgradePinImgResolution(rawImg)
            if (seen.add(upgraded)) {
                result.add(ImportedPinData(title = "Pin ${result.size + 1}", imageUrl = upgraded))
            }
        }
        return result.take(30)
    }

    private fun extractPinImagesFromHtml(html: String): List<ImportedPinData> {
        val result = mutableListOf<ImportedPinData>()
        val seen = mutableSetOf<String>()

        // Prefer og:image first for single pin pages
        val ogRegex = Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""")
        ogRegex.find(html)?.groupValues?.getOrNull(1)?.let { og ->
            val upgraded = upgradePinImgResolution(og)
            if (seen.add(upgraded)) {
                result.add(ImportedPinData("Pinterest Pin", upgraded))
            }
        }

        val imgRegex = Regex("""https://i\.pinimg\.com/(?:736x|originals|564x|474x)/[a-zA-Z0-9/_-]+\.(?:jpg|jpeg|png|webp)""")
        imgRegex.findAll(html).forEach { match ->
            val upgraded = upgradePinImgResolution(match.value)
            if (seen.add(upgraded)) {
                result.add(ImportedPinData("Pin ${result.size + 1}", upgraded))
            }
        }
        return result.take(25)
    }

    private fun upgradePinImgResolution(url: String): String {
        return url
            .replace("/236x/", "/736x/")
            .replace("/474x/", "/736x/")
            .replace("/170x/", "/736x/")
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex("""https?://\S+""")
        return regex.find(text.trim())?.value?.trimEnd(',', '.', ')', ']')
    }

    private fun resolveRedirects(urlStr: String): String {
        var current = urlStr
        repeat(4) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in 300..399 && !loc.isNullOrBlank()) {
                current = loc
            } else {
                return current
            }
        }
        return current
    }

    private fun httpGetText(urlStr: String, authToken: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            throw Exception("HTTP $status: $text")
        }
        return text
    }

    companion object {
        const val REDIRECT_URI = "truelens://pinterest-callback"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) TrueLensCamera/1.0"
    }
}
