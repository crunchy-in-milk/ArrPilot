package io.github.crunchyinmilk.arrpilot

import org.json.JSONObject

data class Movie(
    val title: String,
    val year: Int,
    val overview: String,
    val posterUrl: String?,
    val status: String,
    val downloaded: Boolean,
    val inLibrary: Boolean,
    val added: String,
    val raw: JSONObject
)

data class Choice(val id: Int, val name: String, val path: String = "")

data class QueueItem(
    val title: String,
    val detail: String,
    val progress: Int,
    val raw: JSONObject
)

data class ReleaseOption(
    val title: String,
    val quality: String,
    val sizeBytes: Long,
    val indexer: String,
    val seeders: Int?,
    val leechers: Int?,
    val customFormatScore: Int,
    val rejected: Boolean,
    val downloadAllowed: Boolean,
    val rejections: List<String>,
    val protocol: String,
    val ageHours: Double,
    val publishedAt: String,
    val raw: JSONObject
)

data class PreviewCleanupResult(val resolved: Boolean, val deleted: Boolean)

data class ReleaseCommitResult(val grabbed: Boolean, val monitored: Boolean, val error: String? = null)

enum class ExploreCategory(val label: String, val description: String) {
    TRENDING("Trending", "Getting attention this week"),
    POPULAR("Popular", "TMDB's current popularity ranking"),
    IN_THEATERS("In theaters", "Now playing in US theaters"),
    UPCOMING("Upcoming", "Upcoming US theatrical releases")
}

data class TmdbPage(
    val movies: List<Movie>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int
)

data class TmdbMovieDetails(
    val movie: Movie,
    val runtimeMinutes: Int,
    val certification: String,
    val genres: List<String>,
    val rating: Double,
    val voteCount: Int,
    val trailerId: String?,
    val collectionId: Int?,
    val collectionName: String?,
    val recommendations: List<Movie>,
    val cast: List<CastMember>
)

data class CastMember(
    val tmdbId: Int,
    val name: String,
    val character: String,
    val profileUrl: String?
)

data class LibraryState(val downloaded: Boolean)

enum class DiscoverSort(val label: String, val apiValue: String) {
    POPULARITY("Popularity", "popularity.desc"),
    RATING("Rating", "vote_average.desc"),
    NEWEST("Newest", "primary_release_date.desc"),
    OLDEST("Oldest", "primary_release_date.asc")
}

enum class ReleaseWindow(val label: String) {
    ANY("Any release status"),
    RELEASED("Released"),
    UPCOMING("Upcoming")
}

enum class DiscoverPeriod(val label: String, val startYear: Int?, val endYear: Int?) {
    THIS_YEAR("This year", -1, -1),
    YEARS_2020("2020s", 2020, 2029),
    YEARS_2010("2010s", 2010, 2019),
    YEARS_2000("2000s", 2000, 2009),
    YEARS_1990("1990s", 1990, 1999),
    YEARS_1980("1980s", 1980, 1989),
    YEARS_1970("1970s", 1970, 1979),
    YEARS_1960("1960s", 1960, 1969),
    YEARS_1950("1950s", 1950, 1959),
    YEARS_1940("1940s", 1940, 1949),
    BEFORE_1940("Before 1940", 1870, 1939)
}

data class CustomDiscoverFilter(
    val genreIds: Set<Int> = emptySet(),
    val sort: DiscoverSort = DiscoverSort.POPULARITY,
    val releaseWindow: ReleaseWindow = ReleaseWindow.ANY,
    val periods: Set<DiscoverPeriod> = emptySet(),
    val minimumVotes: Int = 100,
    val maximumVotes: Int = 0,
    val minimumRating: Int = 0,
    val maximumResults: Int = 40,
    val excludeInRadarr: Boolean = false,
    val excludeLikelyShortFilms: Boolean = false,
    val requireTrailer: Boolean = false
)
