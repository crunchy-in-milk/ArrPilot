package io.github.crunchyinmilk.arrpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Random

class TmdbClient(
    context: Context,
    private val readToken: String,
    private val apiKey: String
) {
    private val cache = context.getSharedPreferences("tmdb_response_cache", Context.MODE_PRIVATE)
    private val apiRoot = "https://api.themoviedb.org/3/"

    fun testConnection() {
        request("configuration", 0L)
    }

    fun category(category: ExploreCategory, page: Int): TmdbPage {
        val path = when (category) {
            ExploreCategory.TRENDING -> "trending/movie/week?language=en-US&page=$page"
            ExploreCategory.POPULAR -> "movie/popular?language=en-US&region=US&page=$page"
            ExploreCategory.IN_THEATERS -> "movie/now_playing?language=en-US&region=US&page=$page"
            ExploreCategory.UPCOMING -> "movie/upcoming?language=en-US&region=US&page=$page"
        }
        return parsePage(JSONObject(request(path, 4 * 60 * 60 * 1000L)))
    }

    fun discover(filter: CustomDiscoverFilter, page: Int): TmdbPage {
        val today = LocalDate.now()
        val ranges = periodRanges(filter.periods, today).ifEmpty { listOf(null to null) }
            .mapNotNull { (periodStart, periodEnd) ->
                val start = when (filter.releaseWindow) {
                    ReleaseWindow.UPCOMING -> listOfNotNull(periodStart, today).maxOrNull()
                    else -> periodStart
                }
                val end = when (filter.releaseWindow) {
                    ReleaseWindow.RELEASED -> listOfNotNull(periodEnd, today).minOrNull()
                    else -> periodEnd
                }
                if (start != null && end != null && start.isAfter(end)) null else start to end
            }
        if (ranges.isEmpty()) return TmdbPage(emptyList(), page, page)
        val attempts = ranges.map { (start, end) -> runCatching { discoverRange(filter, page, start, end) } }
        val pages = attempts.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) throw attempts.firstNotNullOf { it.exceptionOrNull() }
        val movies = pages.flatMap { it.movies }.distinctBy { it.raw.optInt("tmdbId") }
        val ordered = sortDiscovered(movies, filter.sort)
        val displayed = if (filter.style.rotatesDaily) {
            ordered.shuffled(Random(stableSeed(filter, today, page, null, null)))
        } else ordered
        return TmdbPage(displayed, page, pages.maxOfOrNull { it.totalPages } ?: page)
    }

    private fun discoverRange(filter: CustomDiscoverFilter, page: Int, startDate: LocalDate?, endDate: LocalDate?): TmdbPage {
        val parameters = linkedMapOf(
            "include_adult" to "false",
            "include_video" to "false",
            "language" to "en-US",
            "sort_by" to filter.sort.apiValue
        )
        if (filter.genreIds.isNotEmpty()) parameters["with_genres"] = filter.genreIds.sorted().joinToString("|")
        if (filter.minimumVotes > 0) parameters["vote_count.gte"] = filter.minimumVotes.toString()
        if (filter.maximumVotes > 0) parameters["vote_count.lte"] = filter.maximumVotes.toString()
        if (filter.minimumRating > 0) parameters["vote_average.gte"] = filter.minimumRating.toString()
        if (filter.style == DiscoveryStyle.CULT_CLASSICS) {
            cultKeywordIds().takeIf(String::isNotBlank)?.let { parameters["with_keywords"] = it }
        }
        startDate?.let { parameters["primary_release_date.gte"] = it.toString() }
        endDate?.let { parameters["primary_release_date.lte"] = it.toString() }
        fun load(sourcePage: Int): TmdbPage {
            parameters["page"] = sourcePage.toString()
            val query = parameters.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
            }
            return parsePage(JSONObject(request("discover/movie?$query", 4 * 60 * 60 * 1000L)))
        }
        if (!filter.style.rotatesDaily) return load(page)
        val first = load(1)
        val availablePages = first.totalPages.coerceIn(1, 20)
        val pageOrder = (1..availablePages).shuffled(Random(stableSeed(filter, LocalDate.now(), 0, startDate, endDate)))
        val sourcePage = pageOrder[(page - 1).mod(availablePages)]
        val selected = if (sourcePage == 1) first else load(sourcePage)
        return selected.copy(page = page, totalPages = availablePages)
    }

    private fun cultKeywordIds(): String {
        val names = listOf("cult film", "b movie", "midnight movie")
        return names.mapNotNull { name ->
            val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
            val results = JSONObject(request("search/keyword?query=$encoded&page=1", 7 * 24 * 60 * 60 * 1000L))
                .optJSONArray("results") ?: JSONArray()
            (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
                ?.optInt("id", 0)?.takeIf { it > 0 }
        }.distinct().joinToString("|")
    }

    private fun stableSeed(
        filter: CustomDiscoverFilter,
        day: LocalDate,
        page: Int,
        startDate: LocalDate?,
        endDate: LocalDate?
    ): Long = listOf(
        day.toEpochDay(), filter.style.name, filter.genreIds.sorted().joinToString(","),
        filter.sort.name, filter.releaseWindow.name, filter.periods.map { it.name }.sorted().joinToString(","),
        filter.minimumVotes, filter.maximumVotes, filter.minimumRating, page, startDate, endDate
    ).joinToString("|").hashCode().toLong()

    private fun periodRanges(periods: Set<DiscoverPeriod>, today: LocalDate): List<Pair<LocalDate, LocalDate>> {
        val ranges = periods.map { period ->
            val startYear = if (period == DiscoverPeriod.THIS_YEAR) today.year else period.startYear!!
            val endYear = if (period == DiscoverPeriod.THIS_YEAR) today.year else period.endYear!!
            LocalDate.of(startYear, 1, 1) to LocalDate.of(endYear, 12, 31)
        }.sortedBy { it.first }
        if (ranges.isEmpty()) return emptyList()
        val merged = mutableListOf<Pair<LocalDate, LocalDate>>()
        ranges.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && !range.first.isAfter(previous.second.plusDays(1))) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, range.second)
            } else merged += range
        }
        return merged
    }

    private fun sortDiscovered(movies: List<Movie>, sort: DiscoverSort): List<Movie> = when (sort) {
        DiscoverSort.POPULARITY -> movies.sortedByDescending { it.raw.optDouble("popularity", 0.0) }
        DiscoverSort.RATING -> movies.sortedWith(
            compareByDescending<Movie> { it.raw.optDouble("vote_average", 0.0) }
                .thenByDescending { it.raw.optInt("vote_count", 0) }
        )
        DiscoverSort.NEWEST -> movies.sortedByDescending { it.raw.optString("release_date") }
        DiscoverSort.OLDEST -> movies.sortedWith(compareBy<Movie> {
            it.raw.optString("release_date").takeIf(String::isNotBlank) ?: "9999-12-31"
        }.thenBy { it.title })
    }

    fun details(tmdbId: Int): TmdbMovieDetails {
        val root = JSONObject(request(
            "movie/$tmdbId?language=en-US&append_to_response=videos,release_dates,recommendations,similar",
            24 * 60 * 60 * 1000L
        ))
        val videos = root.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        val trailerId = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" }
            .sortedByDescending {
                (if (it.optString("type") == "Trailer") 4 else 0) +
                    (if (it.optBoolean("official")) 2 else 0) +
                    (if (it.optString("iso_639_1") == "en") 1 else 0)
            }.firstOrNull()?.optString("key")?.takeIf { it.isNotBlank() }
        val collection = root.optJSONObject("belongs_to_collection")
        val genres = root.optJSONArray("genres") ?: JSONArray()
        val recommendationsRoot = root.optJSONObject("recommendations")?.optJSONArray("results")
            ?.takeIf { it.length() > 0 }
            ?: root.optJSONObject("similar")?.optJSONArray("results") ?: JSONArray()
        return TmdbMovieDetails(
            movie = movie(root),
            runtimeMinutes = root.optInt("runtime", 0),
            certification = certification(root),
            genres = (0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) },
            rating = root.optDouble("vote_average", 0.0),
            voteCount = root.optInt("vote_count", 0),
            trailerId = trailerId,
            collectionId = collection?.optInt("id", 0)?.takeIf { it > 0 },
            collectionName = collection?.optString("name")?.takeIf { it.isNotBlank() },
            recommendations = (0 until recommendationsRoot.length()).mapNotNull { recommendationsRoot.optJSONObject(it)?.let(::movie) }
        )
    }

    fun collection(collectionId: Int): Pair<String, List<Movie>> {
        val root = JSONObject(request("collection/$collectionId?language=en-US", 24 * 60 * 60 * 1000L))
        val parts = root.optJSONArray("parts") ?: JSONArray()
        val movies = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.let(::movie) }
            .sortedWith(compareBy<Movie> { it.year.takeIf { year -> year > 0 } ?: Int.MAX_VALUE }.thenBy { it.title })
        return root.optString("name", "Collection") to movies
    }

    private fun parsePage(root: JSONObject): TmdbPage {
        val results = root.optJSONArray("results") ?: JSONArray()
        return TmdbPage(
            movies = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.let(::movie) },
            page = root.optInt("page", 1),
            totalPages = root.optInt("total_pages", 1).coerceAtMost(500)
        )
    }

    private fun movie(item: JSONObject): Movie {
        val tmdbId = item.optInt("id", item.optInt("tmdbId", 0))
        val releaseDate = item.optString("release_date")
        val year = releaseDate.take(4).toIntOrNull() ?: 0
        val status = if (runCatching { LocalDate.parse(releaseDate).isAfter(LocalDate.now()) }.getOrDefault(false)) "upcoming" else "released"
        val raw = JSONObject(item.toString()).put("tmdbId", tmdbId).put("source", "tmdb")
        return Movie(
            title = item.optString("title", "Untitled"),
            year = year,
            overview = item.optString("overview").ifBlank { "No overview available." },
            posterUrl = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w342$it" },
            status = status,
            downloaded = false,
            inLibrary = false,
            added = "",
            raw = raw
        )
    }

    private fun certification(root: JSONObject): String {
        val countries = root.optJSONObject("release_dates")?.optJSONArray("results") ?: return ""
        for (i in 0 until countries.length()) {
            val country = countries.optJSONObject(i) ?: continue
            if (country.optString("iso_3166_1") != "US") continue
            val dates = country.optJSONArray("release_dates") ?: continue
            val preferred = (0 until dates.length()).mapNotNull { dates.optJSONObject(it) }
                .filter { it.optString("certification").isNotBlank() }
                .sortedBy { if (it.optInt("type") in 3..4) 0 else 1 }
                .firstOrNull()
            return preferred?.optString("certification") ?: ""
        }
        return ""
    }

    private fun body(stream: InputStream): String = stream.bufferedReader().use(BufferedReader::readText)

    private fun request(path: String, ttlMs: Long): String {
        val now = System.currentTimeMillis()
        val cachedAt = cache.getLong("time:$path", 0L)
        val cached = cache.getString("body:$path", null)
        if (cached != null && now - cachedAt < ttlMs) return cached
        var lastError: Exception? = null
        repeat(3) { attempt ->
            if (readToken.isBlank() && apiKey.isBlank()) throw IllegalStateException("TMDB credentials are missing")
            val authenticatedPath = if (readToken.isBlank()) {
                path + if ('?' in path) "&api_key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"
                else "?api_key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"
            } else path
            val conn = (URL(apiRoot + authenticatedPath).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                if (readToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $readToken")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ArrPilot/0.2")
            }
            try {
                val code = conn.responseCode
                val text = body(if (code in 200..299) conn.inputStream else conn.errorStream)
                if (code in 200..299) {
                    cache.edit().putString("body:$path", text).putLong("time:$path", now).apply()
                    return text
                }
                lastError = IllegalStateException("TMDB returned $code: ${text.take(200)}")
                if (code != 429) throw lastError as Exception
                if (attempt < 2) Thread.sleep((conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 1L).coerceAtMost(3L) * 1000L)
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("TMDB request failed")
    }
}
