package io.github.crunchyinmilk.arrpilot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RadarrClient(private val baseUrl: String, private val apiKey: String) {
    private fun api(path: String) = baseUrl.trimEnd('/') + "/api/v3/" + path

    private fun connection(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("X-Api-Key", apiKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ArrPilot/0.1")
        }

    private fun body(stream: InputStream): String = stream.bufferedReader().use(BufferedReader::readText)

    private fun request(path: String, method: String = "GET", payload: JSONObject? = null): String {
        val conn = connection(api(path), method)
        if (payload != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter().use { it.write(payload.toString()) }
        }
        val code = conn.responseCode
        val text = body(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Radarr returned $code: ${text.take(240)}")
        return text
    }

    fun systemStatus(): String = JSONObject(request("system/status")).optString("version", "connected")

    fun qualityProfiles(): List<Choice> {
        val array = JSONArray(request("qualityprofile"))
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Choice(item.getInt("id"), item.getString("name"))
        }
    }

    fun rootFolders(): List<Choice> {
        val array = JSONArray(request("rootfolder"))
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Choice(item.getInt("id"), item.getString("path"), item.getString("path"))
        }
    }

    fun search(term: String): List<Movie> {
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val movies = parseMovies(JSONArray(request("movie/lookup?term=$encoded")), false)
        return rankSearchResults(movies, term)
    }

    fun lookupTmdb(tmdbId: Int): Movie {
        val movies = parseMovies(JSONArray(request("movie/lookup?term=tmdb:$tmdbId")), false)
        return movies.firstOrNull { it.raw.optInt("tmdbId", 0) == tmdbId }
            ?: throw IllegalStateException("Radarr could not find this TMDB movie")
    }

    fun libraryStates(): Map<Int, LibraryState> {
        val array = JSONArray(request("movie?excludeLocalCovers=true"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.optInt("tmdbId", 0).takeIf { it > 0 }?.let {
                it to LibraryState(item.optBoolean("hasFile", false))
            }
        }.toMap()
    }

    private fun parseMovies(array: JSONArray, library: Boolean): List<Movie> =
        (0 until array.length()).map { i -> movieFromJson(array.getJSONObject(i), library) }

    private fun rankSearchResults(movies: List<Movie>, term: String): List<Movie> {
        val queryYear = Regex("\\b(19|20)\\d{2}\\b").find(term)?.value?.toIntOrNull()
        val query = normalizeTitle(term.replace(Regex("\\b(19|20)\\d{2}\\b"), " "))
        val queryTokens = query.split(' ').filter(String::isNotBlank).toSet()
        return movies.withIndex().sortedWith(
            compareByDescending<IndexedValue<Movie>> { indexed ->
                val movie = indexed.value
                val candidates = buildList {
                    add(movie.title)
                    val alternatives = movie.raw.optJSONArray("alternateTitles") ?: JSONArray()
                    for (i in 0 until alternatives.length()) {
                        alternatives.optJSONObject(i)?.optString("title")?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }.map(::normalizeTitle)
                val bestTitleScore = candidates.maxOfOrNull { candidate ->
                    val tokens = candidate.split(' ').filter(String::isNotBlank).toSet()
                    when {
                        candidate == query -> 100_000
                        candidate.startsWith(query) -> 80_000
                        candidate.contains(query) -> 70_000
                        queryTokens.isNotEmpty() && tokens.containsAll(queryTokens) -> 60_000
                        else -> ((queryTokens.intersect(tokens).size.toDouble() / queryTokens.size.coerceAtLeast(1)) * 40_000).toInt()
                    }
                } ?: 0
                bestTitleScore +
                    (if (queryYear != null && movie.year == queryYear) 10_000 else 0) +
                    (if (movie.posterUrl != null) 1_000 else 0)
            }.thenBy { it.index }
        ).map { it.value }
    }

    private fun normalizeTitle(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun movieFromJson(item: JSONObject, library: Boolean): Movie {
        var poster: String? = null
        val images = item.optJSONArray("images") ?: JSONArray()
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            if (image.optString("coverType") == "poster") {
                poster = image.optString("remoteUrl").ifBlank { image.optString("url") }.ifBlank { null }
                break
            }
        }
        if (poster?.startsWith("/") == true) poster = baseUrl.trimEnd('/') + poster
        poster = poster?.replace("image.tmdb.org/t/p/original/", "image.tmdb.org/t/p/w342/")
        val hasFile = item.optBoolean("hasFile", false)
        return Movie(
            title = item.optString("title", "Untitled"),
            year = item.optInt("year", 0),
            overview = item.optString("overview", "No overview available."),
            posterUrl = poster,
            status = item.optString("status", "unknown"),
            downloaded = hasFile,
            inLibrary = library || item.optInt("id", 0) > 0,
            added = item.optString("added", ""),
            raw = item
        )
    }

    fun queue(): List<QueueItem> {
        val response = JSONObject(request("queue?page=1&pageSize=100&includeMovie=true&sortKey=timeleft&sortDirection=ascending"))
        val array = response.optJSONArray("records") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val movie = item.optJSONObject("movie")
            val title = movie?.optString("title")?.takeIf { it.isNotBlank() }
                ?: item.optString("title", "Download")
            val size = item.optDouble("size", 0.0)
            val left = item.optDouble("sizeleft", size)
            val progress = if (size > 0) ((size - left) / size * 100).toInt().coerceIn(0, 100) else 0
            val protocol = item.optString("protocol", "download")
            val eta = item.optString("timeleft", "")
            QueueItem(title, "$progress% • $protocol${if (eta.isBlank()) "" else " • $eta left"}", progress, item)
        }
    }

    fun addTemporaryMovie(movie: Movie, profile: Choice, root: Choice): Movie {
        return addMovie(movie, profile, root, monitored = false)
    }

    fun addMovie(movie: Movie, profile: Choice, root: Choice, monitored: Boolean): Movie {
        val payload = JSONObject(movie.raw.toString())
        payload.remove("id")
        payload.put("qualityProfileId", profile.id)
        payload.put("rootFolderPath", root.path)
        payload.put("monitored", monitored)
        payload.put("minimumAvailability", "released")
        payload.put("addOptions", JSONObject().put("searchForMovie", false))
        return movieFromJson(JSONObject(request("movie", "POST", payload)), true)
    }

    fun cleanupTemporaryMovie(movieId: Int, tmdbId: Int): PreviewCleanupResult {
        val conn = connection(api("movie/$movieId"))
        val code = conn.responseCode
        val text = body(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code == 404) return PreviewCleanupResult(resolved = true, deleted = false)
        if (code !in 200..299) throw IllegalStateException("Radarr returned $code: ${text.take(240)}")

        val movie = JSONObject(text)
        val stillSafeToDelete = movie.optInt("tmdbId", -1) == tmdbId &&
            !movie.optBoolean("monitored", true) &&
            !movie.optBoolean("hasFile", false)
        if (!stillSafeToDelete) return PreviewCleanupResult(resolved = true, deleted = false)

        val queue = JSONArray(request("queue/details?movieId=$movieId"))
        if (queue.length() > 0) {
            return PreviewCleanupResult(resolved = true, deleted = false)
        }

        request("movie/$movieId?deleteFiles=false&addImportExclusion=false", "DELETE")
        return PreviewCleanupResult(resolved = true, deleted = true)
    }

    private fun setMonitored(movie: Movie, monitored: Boolean) {
        val movieId = movie.raw.optInt("id", 0)
        if (movieId <= 0) throw IllegalStateException("Radarr did not return a movie ID")
        val payload = JSONObject(movie.raw.toString()).put("monitored", monitored)
        request("movie/$movieId", "PUT", payload)
    }

    fun grabTemporaryRelease(movie: Movie, release: ReleaseOption): Boolean {
        grabRelease(release)
        return runCatching { setMonitored(movie, true) }.isSuccess
    }

    fun interactiveReleases(movieId: Int): List<ReleaseOption> {
        val array = JSONArray(request("release?movieId=$movieId"))
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val quality = item.optJSONObject("quality")?.optJSONObject("quality")?.optString("name")
                ?: item.optJSONObject("quality")?.optString("name")
                ?: "Unknown quality"
            val rejectionArray = item.optJSONArray("rejections") ?: JSONArray()
            val rejections = (0 until rejectionArray.length()).mapNotNull { rejectionArray.optString(it).takeIf(String::isNotBlank) }
            ReleaseOption(
                title = item.optString("title", "Unnamed release"),
                quality = quality,
                sizeBytes = item.optLong("size", 0L),
                indexer = item.optString("indexer", "Indexer"),
                seeders = if (item.has("seeders") && !item.isNull("seeders")) item.optInt("seeders") else null,
                leechers = if (item.has("leechers") && !item.isNull("leechers")) item.optInt("leechers") else null,
                customFormatScore = item.optInt("customFormatScore", 0),
                rejected = item.optBoolean("rejected", false) || rejections.isNotEmpty(),
                downloadAllowed = item.optBoolean("downloadAllowed", false),
                rejections = rejections,
                protocol = item.optString("protocol", "torrent"),
                ageHours = item.optDouble("ageHours", 0.0),
                publishedAt = item.optString("publishDate", ""),
                raw = item
            )
        }.sortedWith(
            compareBy<ReleaseOption> { it.rejected || !it.downloadAllowed }
                .thenByDescending { it.customFormatScore }
                .thenByDescending { it.seeders ?: -1 }
        )
    }

    fun grabRelease(release: ReleaseOption) {
        request("release", "POST", JSONObject(release.raw.toString()))
    }

    fun loadBitmap(address: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "ArrPilot/0.2")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } finally {
            conn.disconnect()
        }
    }
}
