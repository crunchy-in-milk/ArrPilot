package io.github.crunchyinmilk.arrpilot

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val imageExecutor = Executors.newFixedThreadPool(3)
    private lateinit var client: RadarrClient
    private lateinit var tmdb: TmdbClient
    private lateinit var adapter: MovieAdapter
    private lateinit var releaseAdapter: ReleaseAdapter
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var searchNav: Button
    private lateinit var exploreNav: Button
    private lateinit var queueNav: Button
    private lateinit var profileNav: Button
    private lateinit var settingsNav: Button
    private lateinit var settingsStore: SettingsStore
    private var appSettings = AppSettings()
    private var profiles = emptyList<Choice>()
    private var roots = emptyList<Choice>()
    private var selectedProfile: Choice? = null
    private var selectedRoot: Choice? = null
    private var radarrVersion = ""
    private var connected = false
    private var currentScreen = Screen.SEARCH
    private var searchHasResults = false
    private var temporaryMovie: Movie? = null
    private var previewCommitInProgress = false
    private var releaseReturnScreen = Screen.SEARCH
    private var exploreCategory = ExploreCategory.TRENDING
    private var explorePage = 0
    private var exploreTotalPages = 1
    private var exploreLoading = false
    private var exploreGeneration = 0
    private var exploreTitle = ExploreCategory.TRENDING.label
    private var exploreMovies = emptyList<Movie>()
    private var explorePrevious: ExploreSnapshot? = null
    private var customFilterActive: CustomDiscoverFilter? = null
    private var customDraft = CustomDiscoverFilter()
    private val libraryStates = ConcurrentHashMap<Int, LibraryState>()
    private var libraryStatesLoaded = false
    private var updateBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Dracula.Background
        window.navigationBarColor = Dracula.BackgroundDark
        settingsStore = SettingsStore(this)
        appSettings = settingsStore.load()
        releaseAdapter = ReleaseAdapter(this)
        buildShell()
        if (appSettings.isComplete) connect(appSettings) else showSettings()
        window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) checkForUpdates(false) }, 3000)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(18), dp(36), dp(8))
            setBackgroundColor(Dracula.Background)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text = "ARR PILOT"
            textSize = 22f
            setTextColor(Dracula.Pink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(brand)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), 0, 0, 0)
        }
        searchNav = navButton("Search") { navigateConfigured { leaveReleasePreview(::showSearch) } }
        exploreNav = navButton("Explore") { navigateConfigured { leaveReleasePreview(::showExplore) } }
        queueNav = navButton("Queue") { navigateConfigured { leaveReleasePreview(::loadQueue) } }
        profileNav = navButton("Profile") {
            if (!appSettings.isComplete) return@navButton showSettings()
            setActiveNav(profileNav)
            chooseProfile()
        }
        settingsNav = navButton("Settings") { leaveReleasePreview(::showSettings) }
        searchNav.id = SEARCH_NAV_ID
        exploreNav.id = EXPLORE_NAV_ID
        queueNav.id = QUEUE_NAV_ID
        profileNav.id = PROFILE_NAV_ID
        settingsNav.id = SETTINGS_NAV_ID
        searchNav.nextFocusDownId = SEARCH_INPUT_ID
        exploreNav.nextFocusDownId = EXPLORE_CATEGORY_ID
        queueNav.nextFocusDownId = SEARCH_INPUT_ID
        profileNav.nextFocusDownId = SEARCH_INPUT_ID
        settingsNav.nextFocusDownId = SETTINGS_RADARR_URL_ID
        nav.addView(searchNav)
        nav.addView(exploreNav)
        nav.addView(queueNav)
        nav.addView(profileNav)
        nav.addView(settingsNav)
        header.addView(nav)

        heading = TextView(this).apply {
            textSize = 15f
            setTextColor(Dracula.Foreground)
            setPadding(dp(20), 0, 0, 0)
            visibility = View.GONE
        }
        header.addView(heading)
        header.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = TextView(this).apply {
            text = "Connecting…"
            textSize = 13f
            setTextColor(Dracula.Comment)
        }
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun connect(settings: AppSettings) {
        connected = false
        client = RadarrClient(settings.radarrUrl, settings.radarrApiKey)
        tmdb = TmdbClient(this, settings.tmdbReadToken, settings.tmdbApiKey)
        adapter = MovieAdapter(this, client, imageExecutor)
        updateStatus("Testing connections…")
        networkExecutor.execute {
            try {
                runCatching { cleanupRememberedPreview() }
                val version = client.systemStatus()
                val loadedProfiles = client.qualityProfiles()
                val loadedRoots = client.rootFolders()
                tmdb.testConnection()
                runOnUiThread {
                    profiles = loadedProfiles
                    roots = loadedRoots
                    val prefs = getPreferences(MODE_PRIVATE)
                    selectedProfile = profiles.firstOrNull { it.id == prefs.getInt("profile", -1) } ?: profiles.lastOrNull()
                    selectedRoot = roots.firstOrNull { it.id == prefs.getInt("root", -1) } ?: roots.firstOrNull()
                    customDraft = loadCustomFilter()
                    radarrVersion = version
                    connected = true
                    showSearch()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateStatus("Connection failed")
                    showSettings(error.message)
                }
            }
        }
    }

    private fun showSettings(connectionError: String? = null) {
        currentScreen = Screen.SETTINGS
        heading.visibility = View.GONE
        setActiveNav(settingsNav)
        content.removeAllViews()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(20), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "Connection settings"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Dracula.Foreground)
            })
            addView(infoText("Enter the credentials ArrPilot should use."))
        }
        if (!connectionError.isNullOrBlank()) {
            panel.addView(TextView(this).apply {
                text = "Could not connect: $connectionError"
                textSize = 14f
                setTextColor(Dracula.Red)
                setPadding(0, dp(4), 0, dp(8))
            })
        }

        val radarrUrl = settingsField("Radarr URL", appSettings.radarrUrl, false, SETTINGS_RADARR_URL_ID)
        val radarrKey = settingsField("Radarr API key", appSettings.radarrApiKey, true, SETTINGS_RADARR_KEY_ID)
        val tmdbToken = settingsField("TMDB API Read Access Token (recommended)", appSettings.tmdbReadToken, true, SETTINGS_TMDB_TOKEN_ID)
        val tmdbKey = settingsField("TMDB API key (v3 alternative)", appSettings.tmdbApiKey, true, SETTINGS_TMDB_KEY_ID)
        listOf(radarrUrl, radarrKey, tmdbToken, tmdbKey).forEach { field ->
            panel.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                bottomMargin = dp(8)
            })
        }
        panel.addView(infoText("Provide either a TMDB Read Access Token or a TMDB v3 API key. HTTPS is recommended for Radarr connections outside your home network."))

        val secretFields = listOf(radarrKey, tmdbToken, tmdbKey)
        var credentialsVisible = false
        lateinit var visibilityButton: Button
        visibilityButton = navButton("Show credentials") {
            credentialsVisible = !credentialsVisible
            secretFields.forEach { field ->
                val cursor = field.selectionStart.coerceAtLeast(0)
                field.transformationMethod = if (credentialsVisible) null else PasswordTransformationMethod.getInstance()
                field.typeface = Typeface.DEFAULT
                field.setSelection(cursor.coerceAtMost(field.text.length))
            }
            visibilityButton.text = if (credentialsVisible) "Hide credentials" else "Show credentials"
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val save = navButton("Save & connect") {
            val candidate = AppSettings(
                radarrUrl = radarrUrl.text.toString().trim().trimEnd('/'),
                radarrApiKey = radarrKey.text.toString().trim(),
                tmdbReadToken = tmdbToken.text.toString().trim(),
                tmdbApiKey = tmdbKey.text.toString().trim()
            )
            val error = validateSettings(candidate)
            if (error != null) {
                toast(error)
            } else {
                if (candidate.radarrUrl != appSettings.radarrUrl || candidate.radarrApiKey != appSettings.radarrApiKey) {
                    forgetPreview()
                    getPreferences(MODE_PRIVATE).edit().remove("profile").remove("root").apply()
                }
                appSettings = candidate
                settingsStore.save(candidate)
                profiles = emptyList()
                roots = emptyList()
                selectedProfile = null
                selectedRoot = null
                libraryStates.clear()
                libraryStatesLoaded = false
                exploreMovies = emptyList()
                explorePrevious = null
                customFilterActive = null
                connect(candidate)
            }
        }
        save.nextFocusUpId = SETTINGS_TMDB_KEY_ID
        actions.addView(visibilityButton)
        actions.addView(save)
        actions.addView(navButton("About") { showAbout() })
        actions.addView(navButton("Check for updates") { checkForUpdates(true) })
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateStatus(if (appSettings.isComplete) "Settings" else "Setup required")
        radarrUrl.requestFocus()
    }

    private fun settingsField(hint: String, value: String, secret: Boolean, viewId: Int) = EditText(this).apply {
        id = viewId
        this.hint = hint
        setText(value)
        setHintTextColor(Dracula.Comment)
        setTextColor(Dracula.Foreground)
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(18), 0, dp(18), 0)
        background = getDrawable(R.drawable.search_field)
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        typeface = Typeface.DEFAULT
    }

    private fun validateSettings(settings: AppSettings): String? = when {
        settings.radarrUrl.isBlank() -> "Enter your Radarr URL"
        !settings.radarrUrl.startsWith("https://") && !settings.radarrUrl.startsWith("http://") ->
            "Radarr URL must begin with https:// or http://"
        runCatching { java.net.URL(settings.radarrUrl).host.isNotBlank() }.getOrDefault(false).not() ->
            "Enter a valid Radarr URL"
        settings.radarrApiKey.isBlank() -> "Enter your Radarr API key"
        settings.tmdbReadToken.isBlank() && settings.tmdbApiKey.isBlank() ->
            "Enter a TMDB Read Access Token or API key"
        else -> null
    }

    private fun navigateConfigured(action: () -> Unit) {
        if (connected && appSettings.isComplete && ::client.isInitialized && ::tmdb.isInitialized && ::adapter.isInitialized) action()
        else showSettings()
    }

    private fun showSearch() {
        currentScreen = Screen.SEARCH
        searchHasResults = false
        adapter.replace(emptyList())
        heading.visibility = View.GONE
        setActiveNav(searchNav)
        updateStatus(if (radarrVersion.isBlank()) "Ready" else "Radarr $radarrVersion")
        content.removeAllViews()
        val input = EditText(this).apply {
            id = SEARCH_INPUT_ID
            hint = "Search for movies…"
            setHintTextColor(Dracula.Comment)
            setTextColor(Dracula.Foreground)
            textSize = 18f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), 0, dp(18), 0)
            background = getDrawable(R.drawable.search_field)
            setCompoundDrawablesWithIntrinsicBounds(getDrawable(R.drawable.ic_search), null, null, null)
            compoundDrawablePadding = dp(12)
            nextFocusUpId = SEARCH_NAV_ID
            nextFocusDownId = GRID_ID
        }
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                runSearch(input.text.toString()); true
            } else false
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    searchNav.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (searchHasResults) findGrid()?.requestFocus() == true else false
                }
                else -> false
            }
        }
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val section = TextView(this).apply {
            text = "Movies"
            textSize = 15f
            setTextColor(Dracula.Foreground)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(section)
        content.addView(movieGrid(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        input.requestFocus()
    }

    private fun runSearch(term: String) {
        if (term.isBlank()) return toast("Enter a movie title")
        currentFocus?.let { focused ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        updateStatus("Searching…")
        background({ client.search(term) }, { movies ->
            adapter.replace(movies)
            searchHasResults = movies.isNotEmpty()
            updateStatus("${movies.size} results")
            if (movies.isNotEmpty()) findGrid()?.requestFocus()
        })
    }

    private fun loadQueue() {
        currentScreen = Screen.QUEUE
        heading.text = "Download queue"
        heading.visibility = View.VISIBLE
        setActiveNav(queueNav)
        content.removeAllViews()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateStatus("Loading queue…")
        background({ client.queue() }, { items ->
            list.removeAllViews()
            if (items.isEmpty()) {
                list.addView(infoText("The download queue is empty."))
            } else items.forEach { item ->
                list.addView(queueCard(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)).apply {
                    bottomMargin = dp(10)
                })
            }
            updateStatus("${items.size} active")
            if (items.isNotEmpty()) list.getChildAt(0).requestFocus()
        })
    }

    private fun movieGrid(
        onMovieClick: (Movie) -> Unit = ::showMovie,
        onNearEnd: (() -> Unit)? = null
    ) = GridView(this).apply {
        id = GRID_ID
        val gap = dp(16)
        horizontalSpacing = gap
        verticalSpacing = dp(4)
        stretchMode = GridView.NO_STRETCH
        clipToPadding = true
        isVerticalScrollBarEnabled = false
        setPadding(0, 0, 0, 0)
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        adapter = this@MainActivity.adapter
        setOnItemClickListener { _, _, position, _ -> onMovieClick(this@MainActivity.adapter.getItem(position)) }
        if (onNearEnd != null) {
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 6) onNearEnd()
                }
            })
        }
        post {
            val usableWidth = width - paddingLeft - paddingRight
            val targetCardWidth = dp(150)
            val responsiveColumns = ((usableWidth + gap) / (targetCardWidth + gap)).coerceIn(3, 8)
            numColumns = responsiveColumns
            val available = usableWidth - gap * (responsiveColumns - 1)
            val responsiveWidth = (available / responsiveColumns).coerceAtLeast(dp(120))
            val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(dp(260))
            val contentHeight = responsiveWidth * 3 / 2 + dp(64)
            val responsiveHeight = contentHeight.coerceIn(dp(260), availableHeight)
            columnWidth = responsiveWidth
            this@MainActivity.adapter.setCardSize(responsiveWidth, responsiveHeight)
        }
    }

    private fun showExplore() {
        currentScreen = Screen.EXPLORE
        heading.visibility = View.GONE
        setActiveNav(exploreNav)
        renderExplore()
        if (exploreMovies.isEmpty()) {
            if (customFilterActive != null) loadCustomExplorePage(reset = true) else loadExplorePage(reset = true)
        }
        else updateStatus("${exploreMovies.size} movies")
    }

    private fun renderExplore() {
        content.removeAllViews()
        val categories = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ExploreCategory.entries.forEachIndexed { index, category ->
            categoryRow.addView(navButton(category.label) {
                if (exploreCategory != category || explorePrevious != null || customFilterActive != null) {
                    exploreCategory = category
                    exploreTitle = category.label
                    explorePrevious = null
                    customFilterActive = null
                    renderExplore()
                    findViewById<Button>(EXPLORE_CATEGORY_ID + category.ordinal)?.requestFocus()
                    loadExplorePage(reset = true)
                }
            }.apply {
                id = EXPLORE_CATEGORY_ID + index
                isSelected = explorePrevious == null && customFilterActive == null && exploreCategory == category
                nextFocusUpId = EXPLORE_NAV_ID
                nextFocusDownId = GRID_ID
            })
        }
        categoryRow.addView(navButton("Custom") { showCustomFilterBuilder() }.apply {
            id = CUSTOM_CATEGORY_ID
            isSelected = explorePrevious == null && customFilterActive != null
            nextFocusUpId = EXPLORE_NAV_ID
            nextFocusDownId = GRID_ID
        })
        categories.addView(categoryRow)
        content.addView(categories, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = exploreTitle
                textSize = 15f
                setTextColor(Dracula.Foreground)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val description = when {
                explorePrevious != null -> null
                customFilterActive != null -> customFilterSummary(customFilterActive!!)
                else -> exploreCategory.description
            }
            if (description != null) {
                addView(TextView(this@MainActivity).apply {
                    text = "  —  $description"
                    textSize = 13f
                    setTextColor(Dracula.Comment)
                })
            }
        }
        content.addView(section)
        adapter.replace(exploreMovies)
        content.addView(movieGrid(::showTmdbMovie) {
            if (explorePrevious == null && explorePage < exploreTotalPages) {
                if (customFilterActive != null) loadCustomExplorePage(reset = false) else loadExplorePage(reset = false)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun loadExplorePage(reset: Boolean) {
        if (exploreLoading && !reset) return
        val generation = if (reset) ++exploreGeneration else exploreGeneration
        val category = exploreCategory
        val requestedPage = if (reset) 1 else explorePage + 1
        exploreLoading = true
        if (reset) {
            exploreMovies = emptyList()
            adapter.replace(emptyList())
            updateStatus("Loading ${category.label.lowercase()}…")
        } else updateStatus("Loading more…")
        tmdbBackground({
            if (!libraryStatesLoaded) {
                runCatching { client.libraryStates() }.onSuccess { libraryStates.putAll(it) }
                libraryStatesLoaded = true
            }
            tmdb.category(category, requestedPage)
        }, { page ->
            if (generation != exploreGeneration || category != exploreCategory) return@tmdbBackground
            val marked = page.movies.map(::markLibraryState)
            explorePage = page.page
            exploreTotalPages = page.totalPages
            exploreMovies = if (reset) marked else exploreMovies + marked.filter { candidate ->
                exploreMovies.none { it.raw.optInt("tmdbId") == candidate.raw.optInt("tmdbId") }
            }
            exploreLoading = false
            if (currentScreen == Screen.EXPLORE) {
                if (reset) adapter.replace(exploreMovies) else adapter.append(marked)
                updateStatus("${exploreMovies.size} movies")
                if (reset && marked.isNotEmpty()) findGrid()?.requestFocus()
            }
        }, {
            if (generation == exploreGeneration) exploreLoading = false
        })
    }

    private fun showCustomFilterBuilder() {
        var draft = customFilterActive ?: customDraft
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        lateinit var genreButton: Button
        lateinit var sortButton: Button
        lateinit var releaseButton: Button
        lateinit var periodButton: Button
        lateinit var votesButton: Button
        lateinit var maximumVotesButton: Button
        lateinit var ratingButton: Button
        lateinit var resultsButton: Button
        lateinit var radarrButton: Button
        genreButton = filterSettingButton("Genres", genresSummary(draft.genreIds)) {
            showGenrePicker(draft.genreIds) { ids ->
                draft = draft.copy(genreIds = ids)
                genreButton.text = filterSettingText("Genres", genresSummary(ids))
            }
        }
        sortButton = filterSettingButton("Sort", draft.sort.label) {
            chooseFilterOption("Sort movies", DiscoverSort.entries.map { it.label }, draft.sort.ordinal) { which ->
                draft = draft.copy(sort = DiscoverSort.entries[which])
                sortButton.text = filterSettingText("Sort", draft.sort.label)
            }
        }
        releaseButton = filterSettingButton("Release", draft.releaseWindow.label) {
            chooseFilterOption("Release status", ReleaseWindow.entries.map { it.label }, draft.releaseWindow.ordinal) { which ->
                draft = draft.copy(releaseWindow = ReleaseWindow.entries[which])
                releaseButton.text = filterSettingText("Release", draft.releaseWindow.label)
            }
        }
        periodButton = filterSettingButton("Years", periodsSummary(draft.periods)) {
            showPeriodPicker(draft.periods) { periods ->
                draft = draft.copy(periods = periods)
                periodButton.text = filterSettingText("Years", periodsSummary(periods))
            }
        }
        val voteOptions = listOf(0, 10, 25, 50, 100, 250, 500, 1_000, 5_000)
        votesButton = filterSettingButton("Minimum ratings", formatRatingCount(draft.minimumVotes)) {
            chooseFilterOption("Minimum number of ratings", voteOptions.map(::formatRatingCount), voteOptions.indexOf(draft.minimumVotes).coerceAtLeast(0)) { which ->
                draft = draft.copy(minimumVotes = voteOptions[which])
                votesButton.text = filterSettingText("Minimum ratings", formatRatingCount(draft.minimumVotes))
            }
        }
        val maximumVoteOptions = listOf(0, 100, 250, 500, 1_000, 2_000, 5_000, 10_000, 50_000)
        maximumVotesButton = filterSettingButton("Maximum ratings", formatRatingCount(draft.maximumVotes)) {
            chooseFilterOption("Maximum number of ratings", maximumVoteOptions.map(::formatRatingCount), maximumVoteOptions.indexOf(draft.maximumVotes).coerceAtLeast(0)) { which ->
                draft = draft.copy(maximumVotes = maximumVoteOptions[which])
                maximumVotesButton.text = filterSettingText("Maximum ratings", formatRatingCount(draft.maximumVotes))
            }
        }
        val ratingOptions = listOf(0, 5, 6, 7, 8)
        ratingButton = filterSettingButton("Minimum score", formatMinimumScore(draft.minimumRating)) {
            chooseFilterOption("Minimum TMDB score", ratingOptions.map { if (it == 0) "Any" else "$it/10" }, ratingOptions.indexOf(draft.minimumRating).coerceAtLeast(0)) { which ->
                draft = draft.copy(minimumRating = ratingOptions[which])
                ratingButton.text = filterSettingText("Minimum score", formatMinimumScore(draft.minimumRating))
            }
        }
        // Zero is persisted as an unlimited result count; results still arrive one
        // TMDB page at a time as the user approaches the end of the grid.
        val resultOptions = listOf(20, 40, 60, 0)
        fun resultLimitLabel(value: Int) = if (value == 0) "No limit" else value.toString()
        resultsButton = filterSettingButton("Maximum results", resultLimitLabel(draft.maximumResults)) {
            chooseFilterOption("Maximum results", resultOptions.map(::resultLimitLabel), resultOptions.indexOf(draft.maximumResults).coerceAtLeast(0)) { which ->
                draft = draft.copy(maximumResults = resultOptions[which])
                resultsButton.text = filterSettingText("Maximum results", resultLimitLabel(draft.maximumResults))
            }
        }
        radarrButton = filterSettingButton("Movies in Radarr", if (draft.excludeInRadarr) "Hide" else "Include") {
            val values = listOf("Include", "Hide")
            chooseFilterOption("Movies already in Radarr", values, if (draft.excludeInRadarr) 1 else 0) { which ->
                draft = draft.copy(excludeInRadarr = which == 1)
                radarrButton.text = filterSettingText("Movies in Radarr", values[which])
            }
        }
        listOf(genreButton, sortButton, releaseButton, periodButton, votesButton, maximumVotesButton, ratingButton, resultsButton, radarrButton)
            .forEach { panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(2) }) }
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom discovery")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Show movies") { _, _ -> applyCustomFilter(draft) }
            .create()
        dialog.setOnShowListener {
            val screenWidth = resources.displayMetrics.widthPixels
            dialog.window?.setLayout((screenWidth * 0.68f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            genreButton.requestFocus()
        }
        dialog.show()
    }

    private fun filterSettingButton(label: String, value: String, action: () -> Unit) =
        Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
        text = filterSettingText(label, value)
        textSize = 15f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { action() }
    }

    private fun filterSettingText(label: String, value: String) = "$label:  $value"

    private fun formatRatingCount(value: Int) = if (value == 0) "Any" else String.format(Locale.US, "%,d", value)

    private fun formatMinimumScore(value: Int) = if (value == 0) "Any" else "$value/10"

    private fun chooseFilterOption(title: String, labels: List<String>, current: Int, selected: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                selected(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGenrePicker(current: Set<Int>, selected: (Set<Int>) -> Unit) {
        val choices = MOVIE_GENRES.map { it.second }.toTypedArray()
        val working = current.toMutableSet()
        val checked = MOVIE_GENRES.map { working.contains(it.first) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Genres — match any selected")
            .setMultiChoiceItems(choices, checked) { _, which, enabled ->
                if (enabled) working += MOVIE_GENRES[which].first else working -= MOVIE_GENRES[which].first
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> selected(emptySet()) }
            .setPositiveButton("Apply") { _, _ -> selected(working) }
            .show()
    }

    private fun showPeriodPicker(current: Set<DiscoverPeriod>, selected: (Set<DiscoverPeriod>) -> Unit) {
        val periods = DiscoverPeriod.entries
        val working = current.toMutableSet()
        val checked = periods.map(working::contains).toBooleanArray()
        AlertDialog.Builder(this).setTitle("Years and decades — match any selected")
            .setMultiChoiceItems(periods.map { it.label }.toTypedArray(), checked) { _, which, enabled ->
                if (enabled) working += periods[which] else working -= periods[which]
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> selected(emptySet()) }
            .setPositiveButton("Apply") { _, _ -> selected(working) }
            .show()
    }

    private fun applyCustomFilter(filter: CustomDiscoverFilter) {
        customDraft = filter
        saveCustomFilter(filter)
        customFilterActive = filter
        explorePrevious = null
        exploreTitle = "Custom"
        explorePage = 0
        exploreTotalPages = 1
        exploreMovies = emptyList()
        renderExplore()
        loadCustomExplorePage(reset = true)
    }

    private fun loadCustomExplorePage(reset: Boolean) {
        val filter = customFilterActive ?: return
        if (exploreLoading && !reset) return
        val limitReached = filter.maximumResults > 0 && exploreMovies.size >= filter.maximumResults
        if (!reset && limitReached) return
        val generation = if (reset) ++exploreGeneration else exploreGeneration
        val requestedPage = if (reset) 1 else explorePage + 1
        exploreLoading = true
        if (reset) {
            adapter.replace(emptyList())
            updateStatus("Building custom results…")
        } else updateStatus("Loading more…")
        tmdbBackground({
            if (!libraryStatesLoaded) {
                runCatching { client.libraryStates() }.onSuccess { libraryStates.putAll(it) }
                libraryStatesLoaded = true
            }
            tmdb.discover(filter, requestedPage)
        }, { page ->
            if (generation != exploreGeneration || customFilterActive != filter) return@tmdbBackground
            val marked = page.movies.map(::markLibraryState)
            val eligible = marked.filter { !filter.excludeInRadarr || !it.inLibrary }
            val remaining = if (filter.maximumResults == 0) Int.MAX_VALUE
            else (filter.maximumResults - if (reset) 0 else exploreMovies.size).coerceAtLeast(0)
            val additions = eligible.take(remaining)
            explorePage = page.page
            exploreTotalPages = page.totalPages
            exploreMovies = if (reset) additions else exploreMovies + additions.filter { candidate ->
                exploreMovies.none { it.raw.optInt("tmdbId") == candidate.raw.optInt("tmdbId") }
            }
            exploreLoading = false
            if (currentScreen == Screen.EXPLORE && customFilterActive == filter) {
                if (reset) adapter.replace(exploreMovies) else adapter.append(additions)
                updateStatus("${exploreMovies.size} custom results")
                if (reset && exploreMovies.isNotEmpty()) findGrid()?.requestFocus()
                val moreAllowed = filter.maximumResults == 0 || exploreMovies.size < filter.maximumResults
                if (additions.isEmpty() && explorePage < exploreTotalPages && moreAllowed) {
                    loadCustomExplorePage(reset = false)
                }
            }
        }, {
            if (generation == exploreGeneration) exploreLoading = false
        })
    }

    private fun genresSummary(ids: Set<Int>): String {
        if (ids.isEmpty()) return "Any"
        val names = MOVIE_GENRES.filter { ids.contains(it.first) }.map { it.second }
        return if (names.size <= 2) names.joinToString(", ") else "${names.take(2).joinToString(", ")} +${names.size - 2}"
    }

    private fun periodsSummary(periods: Set<DiscoverPeriod>): String {
        if (periods.isEmpty()) return "Any year"
        val labels = DiscoverPeriod.entries.filter(periods::contains).map { it.label }
        return if (labels.size <= 2) labels.joinToString(", ") else "${labels.take(2).joinToString(", ")} +${labels.size - 2}"
    }

    private fun customFilterSummary(filter: CustomDiscoverFilter): String = buildList {
        add(if (filter.genreIds.isEmpty()) "All genres" else genresSummary(filter.genreIds))
        add(filter.sort.label)
        if (filter.releaseWindow != ReleaseWindow.ANY) add(filter.releaseWindow.label)
        if (filter.periods.isNotEmpty()) add(periodsSummary(filter.periods))
        if (filter.minimumVotes > 0) add("${String.format(Locale.US, "%,d", filter.minimumVotes)}+ ratings")
        if (filter.maximumVotes > 0) add("under ${String.format(Locale.US, "%,d", filter.maximumVotes)} ratings")
        if (filter.minimumRating > 0) add("${filter.minimumRating}+/10")
        if (filter.excludeInRadarr) add("Hiding Radarr movies")
    }.joinToString(" • ")

    private fun markLibraryState(movie: Movie): Movie {
        val state = libraryStates[movie.raw.optInt("tmdbId", 0)] ?: return movie
        return movie.copy(inLibrary = true, downloaded = state.downloaded)
    }

    private fun showTmdbMovie(movie: Movie) {
        updateStatus("Loading details…")
        tmdbBackground({ tmdb.details(movie.raw.optInt("tmdbId", 0)) }, { details ->
            if (currentScreen != Screen.EXPLORE) return@tmdbBackground
            updateStatus("${exploreMovies.size} movies")
            showTmdbDetails(details.copy(movie = markLibraryState(details.movie)))
        })
    }

    private fun showTmdbDetails(details: TmdbMovieDetails) {
        val movie = details.movie
        val facts = buildList {
            movie.year.takeIf { it > 0 }?.let { add(it.toString()) }
            details.certification.takeIf(String::isNotBlank)?.let(::add)
            details.runtimeMinutes.takeIf { it > 0 }?.let { add("${it / 60}h ${it % 60}m") }
            if (details.rating > 0) add(String.format(Locale.US, "TMDB %.1f/10 (%,d)", details.rating, details.voteCount))
            if (movie.downloaded) add("Downloaded") else if (movie.inLibrary) add("In Radarr")
        }
        val message = buildString {
            append(facts.joinToString(" • "))
            if (details.genres.isNotEmpty()) append("\n${details.genres.joinToString(" • ")}")
            append("\n\n${movie.overview}")
        }
        val radarrLabel = if (!movie.inLibrary) "Radarr options" else "View releases"
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(dp(18), 0, dp(18), dp(8))
            addView(actionRow, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(movie.title)
            .setMessage(message)
            .setView(actionScroll)
            .create()
        details.trailerId?.let { trailerId ->
            actionRow.addView(dialogActionButton("Trailer") {
                dialog.dismiss()
                openTrailer(trailerId)
            })
        }
        if (details.recommendations.isNotEmpty()) {
            actionRow.addView(dialogActionButton("More like this") {
                dialog.dismiss()
                showCustomExplore("More like ${details.movie.title}", details.recommendations)
            })
        }
        details.collectionId?.let { collectionId ->
            actionRow.addView(dialogActionButton("View collection") {
                dialog.dismiss()
                loadCollection(collectionId)
            })
        }
        actionRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        actionRow.addView(dialogActionButton("Close") { dialog.dismiss() })
        val radarrButton = dialogActionButton(radarrLabel) {
            dialog.dismiss()
            if (!movie.inLibrary) showRadarrOptions(movie)
            else viewTmdbReleases(movie)
        }
        actionRow.addView(radarrButton)
        dialog.setOnShowListener {
            val screenWidth = resources.displayMetrics.widthPixels
            val dialogWidth = (screenWidth * 0.88f).toInt().coerceAtMost(screenWidth - dp(48))
            dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            radarrButton.requestFocus()
        }
        dialog.show()
    }

    private fun dialogActionButton(label: String, action: () -> Unit) =
        Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            text = label
            isAllCaps = true
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
        }

    private fun showRadarrOptions(movie: Movie) {
        val actions = arrayOf(
            "Preview available releases",
            "Add monitored",
            "Add unmonitored"
        )
        AlertDialog.Builder(this)
            .setTitle("Add ${movie.title}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> viewTmdbReleases(movie)
                    1 -> chooseProfileForMovie(movie, monitored = true)
                    2 -> chooseProfileForMovie(movie, monitored = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseProfileForMovie(movie: Movie, monitored: Boolean) {
        if (profiles.isEmpty()) return toast("Radarr has no available quality profile")
        val root = selectedRoot ?: return toast("Radarr has no available root folder")
        val current = profiles.indexOf(selectedProfile).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose quality profile")
            .setSingleChoiceItems(profiles.map { it.name }.toTypedArray(), current) { dialog, which ->
                val profile = profiles[which]
                selectedProfile = profile
                getPreferences(MODE_PRIVATE).edit().putInt("profile", profile.id).apply()
                dialog.dismiss()
                addPermanentMovie(movie, profile, root, monitored)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPermanentMovie(movie: Movie, profile: Choice, root: Choice, monitored: Boolean) {
        updateStatus("Adding to Radarr…")
        background({
            val radarrMovie = client.lookupTmdb(movie.raw.optInt("tmdbId", 0))
            if (radarrMovie.inLibrary) radarrMovie else client.addMovie(radarrMovie, profile, root, monitored)
        }, { added ->
            val tmdbId = added.raw.optInt("tmdbId", 0)
            if (tmdbId > 0) libraryStates[tmdbId] = LibraryState(added.downloaded)
            exploreMovies = exploreMovies.map(::markLibraryState)
            if (currentScreen == Screen.EXPLORE) adapter.replace(exploreMovies)
            updateStatus("Added to Radarr")
            toast("${added.title} added to Radarr as ${if (monitored) "monitored" else "unmonitored"} with ${profile.name}")
        })
    }

    private fun loadCollection(collectionId: Int) {
        updateStatus("Loading collection…")
        tmdbBackground({ tmdb.collection(collectionId) }, { result ->
            showCustomExplore(result.first, result.second)
        })
    }

    private fun showCustomExplore(title: String, movies: List<Movie>) {
        if (currentScreen == Screen.EXPLORE && explorePrevious == null) {
            explorePrevious = ExploreSnapshot(exploreCategory, exploreTitle, explorePage, exploreTotalPages, exploreMovies, customFilterActive)
        }
        currentScreen = Screen.EXPLORE
        customFilterActive = null
        exploreTitle = title
        explorePage = 1
        exploreTotalPages = 1
        exploreMovies = movies.map(::markLibraryState)
        setActiveNav(exploreNav)
        renderExplore()
        updateStatus("${exploreMovies.size} movies")
        findGrid()?.requestFocus()
    }

    private fun viewTmdbReleases(movie: Movie) {
        updateStatus("Checking Radarr…")
        background({ client.lookupTmdb(movie.raw.optInt("tmdbId", 0)) }, { radarrMovie ->
            if (radarrMovie.inLibrary) loadReleases(radarrMovie) else addForInteractiveSearch(radarrMovie)
        })
    }

    private fun showMovie(movie: Movie) {
        val trailerId = movie.raw.optString("youTubeTrailerId").trim()
        val text = buildString {
            if (movie.year > 0) append("${movie.year} • ")
            append(movie.status.replaceFirstChar { it.uppercase() })
            if (movie.downloaded) append(" • Downloaded")
            append("\n\n${movie.overview}")
            if (!movie.inLibrary) {
                append("\n\nProfile: ${selectedProfile?.name ?: "not available"}")
                append("\nFolder: ${selectedRoot?.path ?: "not available"}")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(movie.title)
            .setMessage(text)
            .setNegativeButton("Close", null)
        if (!movie.inLibrary) {
            dialog.setPositiveButton("Radarr options") { _, _ -> showRadarrOptions(movie) }
        } else {
            dialog.setPositiveButton("Search releases") { _, _ -> loadReleases(movie) }
        }
        if (trailerId.isNotEmpty()) {
            dialog.setNeutralButton("Trailer") { _, _ -> openTrailer(trailerId) }
        }
        dialog.show()
    }

    private fun openTrailer(trailerId: String) {
        val webUrl = Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(trailerId)}")
        val youtubeTvIntent = Intent(Intent.ACTION_VIEW, webUrl).apply {
            setPackage(YOUTUBE_TV_PACKAGE)
        }
        try {
            startActivity(youtubeTvIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, webUrl))
            } catch (_: ActivityNotFoundException) {
                toast("No app is available to play this trailer")
            }
        }
    }

    private fun addForInteractiveSearch(movie: Movie) {
        val profile = selectedProfile
        val root = selectedRoot
        if (profile == null || root == null) return toast("Radarr has no available profile or root folder")
        updateStatus("Preparing release preview…")
        background({
            client.addTemporaryMovie(movie, profile, root).also(::rememberPreview)
        }, { added ->
            temporaryMovie = added
            loadReleases(added)
        })
    }

    private fun loadReleases(movie: Movie) {
        val movieId = movie.raw.optInt("id", 0)
        if (movieId <= 0) return toast("Radarr did not return a movie ID")
        if (currentScreen != Screen.RELEASES) releaseReturnScreen = currentScreen
        currentScreen = Screen.RELEASES
        heading.text = "Releases · ${movie.title}"
        heading.visibility = View.VISIBLE
        setActiveNav(searchNav)
        content.removeAllViews()
        content.addView(infoText("Interactive search — nothing downloads until you approve one exact release."))
        val loading = infoText("Searching your configured indexers… this can take a little while.")
        content.addView(loading)
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(9)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = releaseAdapter
            setOnItemClickListener { _, _, position, _ -> showRelease(releaseAdapter.getItem(position)) }
        }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateStatus("Searching indexers…")
        background({ client.interactiveReleases(movieId) }, { releases ->
            releaseAdapter.replace(releases)
            content.removeView(loading)
            updateStatus("${releases.size} releases")
            if (releases.isEmpty()) {
                content.addView(infoText("No releases were returned by Radarr. The temporary movie will be removed."), 1)
                discardTemporaryMovie()
            }
            else list.requestFocus()
        })
    }

    private fun showRelease(release: ReleaseOption) {
        val size = if (release.sizeBytes > 0) String.format(java.util.Locale.US, "%.2f GiB", release.sizeBytes / 1_073_741_824.0) else "Unknown"
        val eligible = !release.rejected && release.downloadAllowed
        val availability = when {
            release.rejections.isNotEmpty() -> "Rejected by Radarr:\n${release.rejections.joinToString("\n") { "• $it" }}"
            !release.downloadAllowed -> "Radarr reports that downloading is disabled for this release."
            else -> "Eligible under the selected profile."
        }
        val message = buildString {
            append("${release.quality} • $size • ${release.protocol}\n")
            append("Indexer: ${release.indexer}\n")
            append("Seeders: ${release.seeders ?: "unknown"} • Leechers: ${release.leechers ?: "unknown"}\n")
            append("Custom format score: ${release.customFormatScore}\n")
            append("Uploaded: ${formatPublishedAt(release)}\n\n")
            append(availability)
            if (eligible) append("\n\nConfirming sends only this exact release to Radarr's download client.")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (eligible) "Download this exact release?" else "Release not eligible")
            .setMessage(message)
            .setNegativeButton(if (eligible) "Cancel" else "Back", null)
        if (eligible) {
            dialog.setPositiveButton("Download") { _, _ ->
                updateStatus("Sending selected release…")
                val preview = temporaryMovie
                if (preview == null) {
                    background({ client.grabRelease(release) }, {
                        toast("Selected release sent to the download client")
                        loadQueue()
                    })
                } else {
                    previewCommitInProgress = true
                    background({
                        runCatching {
                            val monitored = client.grabTemporaryRelease(preview, release)
                            ReleaseCommitResult(grabbed = true, monitored = monitored)
                        }.getOrElse {
                            ReleaseCommitResult(false, false, it.message ?: it.javaClass.simpleName)
                        }
                    }, { result ->
                        previewCommitInProgress = false
                        if (result.grabbed) {
                            forgetPreview()
                            temporaryMovie = null
                            toast(if (result.monitored) {
                                "Selected release sent to the download client"
                            } else {
                                "Release sent, but Radarr could not mark the movie monitored"
                            })
                            loadQueue()
                        } else {
                            updateStatus("Release was not sent")
                            AlertDialog.Builder(this).setTitle("Could not send release")
                                .setMessage(result.error ?: "Unknown error")
                                .setPositiveButton("OK", null).show()
                        }
                    })
                }
            }
        }
        dialog.show()
    }

    private fun formatPublishedAt(release: ReleaseOption): String {
        val absolute = runCatching { Instant.parse(release.publishedAt) }.getOrNull()
            ?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
            ?: "Unknown date"
        val relative = when {
            release.ageHours < 1.0 -> "less than an hour ago"
            release.ageHours < 24.0 -> "${release.ageHours.toInt()} hours ago"
            else -> "${(release.ageHours / 24.0).toInt()} days ago"
        }
        return "$absolute ($relative)"
    }

    private fun chooseProfile(after: (() -> Unit)? = null) {
        if (profiles.isEmpty()) return toast("Profiles have not loaded yet")
        val labels = profiles.map { it.name }.toTypedArray()
        val current = profiles.indexOf(selectedProfile).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Quality profile").setSingleChoiceItems(labels, current) { dialog, which ->
            selectedProfile = profiles[which]
            getPreferences(MODE_PRIVATE).edit().putInt("profile", profiles[which].id).apply()
            dialog.dismiss()
            chooseRoot(after)
        }.setNegativeButton("Cancel", null)
            .setOnDismissListener { updateActiveNav() }
            .show()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateBusy) { if (manual) toast("An update check or download is already running"); return }
        val prefs = getPreferences(MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("update_check_time", 0) < 6 * 60 * 60 * 1000L) return
        updateBusy = true
        if (manual) toast("Checking for updates…")
        networkExecutor.execute {
            val result = runCatching { AppUpdates(this).latest() }
            if (result.isSuccess) prefs.edit().putLong("update_check_time", now).apply()
            runOnUiThread {
                updateBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ release ->
                    if (release == null) {
                        if (manual) AlertDialog.Builder(this).setTitle("ArrPilot is up to date")
                            .setMessage("Installed version: ${BuildConfig.VERSION_NAME}")
                            .setPositiveButton("Close", null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("ArrPilot ${release.version} available")
                            .setMessage("Download this update from GitHub? Android will ask you to confirm installation. Your settings will be kept.")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Download") { _, _ -> downloadUpdate(release) }.show()
                    }
                }, { error -> if (manual) showUpdateError(error) })
            }
        }
    }

    private fun downloadUpdate(release: AppRelease) {
        if (updateBusy) return
        updateBusy = true
        toast("Downloading ArrPilot ${release.version}…")
        networkExecutor.execute {
            val result = runCatching { AppUpdates(this).download(release) }
            runOnUiThread {
                updateBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ installUpdate() }, ::showUpdateError)
            }
        }
    }

    private fun installUpdate() {
        try {
            AppUpdates(this).verify(java.io.File(cacheDir, "update.apk"))
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this).setTitle("Allow ArrPilot updates")
                    .setMessage("Enable installation from ArrPilot on the next screen, then return here to continue.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")), 9401)
                        } catch (error: Exception) { showUpdateError(error) }
                    }.show()
                return
            }
            val uri = Uri.parse("content://$packageName.updates/update.apk")
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("ArrPilot update", uri)
            })
        } catch (error: Exception) { showUpdateError(error) }
    }

    @Deprecated("Retained for installation permission flow on Android TV")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9401) {
            if (packageManager.canRequestPackageInstalls()) installUpdate()
            else toast("Installation permission was not enabled. You can retry from Settings.")
        }
    }

    private fun showUpdateError(error: Throwable) {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Could not update ArrPilot")
            .setMessage(error.message ?: "Please try again later.").setPositiveButton("Close", null).show()
    }

    private fun showAbout() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Version ${BuildConfig.VERSION_NAME}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Dracula.Foreground)
                setPadding(0, 0, 0, dp(12))
            })
            addView(infoText("A lightweight, remote-friendly Android TV frontend for searching Radarr, reviewing releases, and discovering movies."))
            addView(infoText("ArrPilot is an independent, unofficial project. It is not affiliated with or endorsed by Radarr."))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.tmdb_logo)
                contentDescription = "TMDB"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(74), dp(54)).apply {
                gravity = Gravity.START
                topMargin = dp(4)
                bottomMargin = dp(8)
            })
            addView(infoText("Movie discovery data and images are provided by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."))
            addView(infoText("Licensed under GPL-3.0.\ngithub.com/crunchy-in-milk/ArrPilot"))
        }
        AlertDialog.Builder(this)
            .setTitle("About ArrPilot")
            .setView(body)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun chooseRoot(after: (() -> Unit)? = null) {
        if (roots.isEmpty()) return toast("Radarr has no accessible root folder")
        val labels = roots.map { it.path }.toTypedArray()
        val current = roots.indexOf(selectedRoot).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Root folder").setSingleChoiceItems(labels, current) { dialog, which ->
            selectedRoot = roots[which]
            getPreferences(MODE_PRIVATE).edit().putInt("root", roots[which].id).apply()
            dialog.dismiss()
            updateStatus("${selectedProfile?.name} • ${selectedRoot?.path}")
            after?.invoke()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun queueCard(item: QueueItem) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        background = getDrawable(R.drawable.focusable_panel)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        addView(TextView(this@MainActivity).apply {
            text = item.title
            textSize = 18f
            setTextColor(Dracula.Foreground)
        })
        addView(TextView(this@MainActivity).apply {
            text = item.detail
            textSize = 13f
            setTextColor(Dracula.Comment)
        })
    }

    private fun navButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Dracula.Foreground)
        background = getDrawable(R.drawable.button_tv)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnFocusChangeListener { _, focused -> setTextColor(if (focused) Dracula.Foreground else Dracula.Foreground) }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(6) }
    }

    private fun setActiveNav(active: Button) {
        searchNav.isSelected = active === searchNav
        exploreNav.isSelected = active === exploreNav
        queueNav.isSelected = active === queueNav
        profileNav.isSelected = active === profileNav
        settingsNav.isSelected = active === settingsNav
    }

    private fun updateActiveNav() {
        setActiveNav(when (currentScreen) {
            Screen.EXPLORE -> exploreNav
            Screen.QUEUE -> queueNav
            Screen.SETTINGS -> settingsNav
            else -> searchNav
        })
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Dracula.Comment)
        setPadding(0, 0, 0, dp(8))
    }

    private fun findGrid() = findViewById<GridView>(GRID_ID)

    private fun rememberPreview(movie: Movie) {
        getPreferences(MODE_PRIVATE).edit()
            .putInt(PREVIEW_ID_KEY, movie.raw.optInt("id", 0))
            .putInt(PREVIEW_TMDB_KEY, movie.raw.optInt("tmdbId", 0))
            .commit()
    }

    private fun forgetPreview() {
        getPreferences(MODE_PRIVATE).edit()
            .remove(PREVIEW_ID_KEY)
            .remove(PREVIEW_TMDB_KEY)
            .apply()
    }

    private fun cleanupRememberedPreview() {
        val prefs = getPreferences(MODE_PRIVATE)
        val movieId = prefs.getInt(PREVIEW_ID_KEY, 0)
        val tmdbId = prefs.getInt(PREVIEW_TMDB_KEY, 0)
        if (movieId <= 0 || tmdbId <= 0) {
            forgetPreview()
            return
        }
        val result = client.cleanupTemporaryMovie(movieId, tmdbId)
        if (result.resolved) forgetPreview()
    }

    private fun loadCustomFilter(): CustomDiscoverFilter {
        val prefs = getPreferences(MODE_PRIVATE)
        val genreIds = prefs.getString(CUSTOM_GENRES_KEY, "").orEmpty().split(',')
            .mapNotNull(String::toIntOrNull).toSet()
        return CustomDiscoverFilter(
            genreIds = genreIds,
            sort = runCatching { DiscoverSort.valueOf(prefs.getString(CUSTOM_SORT_KEY, null).orEmpty()) }.getOrDefault(DiscoverSort.POPULARITY),
            releaseWindow = runCatching { ReleaseWindow.valueOf(prefs.getString(CUSTOM_RELEASE_KEY, null).orEmpty()) }.getOrDefault(ReleaseWindow.ANY),
            periods = prefs.getString(CUSTOM_PERIOD_KEY, "").orEmpty().split(',')
                .mapNotNull { name -> runCatching { DiscoverPeriod.valueOf(name) }.getOrNull() }.toSet(),
            minimumVotes = prefs.getInt(CUSTOM_VOTES_KEY, 100),
            maximumVotes = prefs.getInt(CUSTOM_MAX_VOTES_KEY, 0),
            minimumRating = prefs.getInt(CUSTOM_RATING_KEY, 0),
            maximumResults = prefs.getInt(CUSTOM_RESULTS_KEY, 40),
            excludeInRadarr = prefs.getBoolean(CUSTOM_EXCLUDE_RADARR_KEY, false)
        )
    }

    private fun saveCustomFilter(filter: CustomDiscoverFilter) {
        getPreferences(MODE_PRIVATE).edit()
            .putString(CUSTOM_GENRES_KEY, filter.genreIds.sorted().joinToString(","))
            .putString(CUSTOM_SORT_KEY, filter.sort.name)
            .putString(CUSTOM_RELEASE_KEY, filter.releaseWindow.name)
            .putString(CUSTOM_PERIOD_KEY, filter.periods.joinToString(",") { it.name })
            .putInt(CUSTOM_VOTES_KEY, filter.minimumVotes)
            .putInt(CUSTOM_MAX_VOTES_KEY, filter.maximumVotes)
            .putInt(CUSTOM_RATING_KEY, filter.minimumRating)
            .putInt(CUSTOM_RESULTS_KEY, filter.maximumResults)
            .putBoolean(CUSTOM_EXCLUDE_RADARR_KEY, filter.excludeInRadarr)
            .apply()
    }

    private fun discardTemporaryMovie() {
        val preview = temporaryMovie ?: return
        temporaryMovie = null
        val movieId = preview.raw.optInt("id", 0)
        val tmdbId = preview.raw.optInt("tmdbId", 0)
        if (movieId <= 0 || tmdbId <= 0) return
        background({ client.cleanupTemporaryMovie(movieId, tmdbId) }, { result ->
            if (result.resolved) forgetPreview()
        })
    }

    private fun leaveReleasePreview(destination: () -> Unit) {
        if (currentScreen != Screen.RELEASES || temporaryMovie == null) {
            destination()
            return
        }
        if (previewCommitInProgress) {
            toast("Please wait while Radarr sends the selected release")
            return
        }
        discardTemporaryMovie()
        destination()
    }

    private fun <T> background(work: () -> T, success: (T) -> Unit) {
        networkExecutor.execute {
            try {
                val result = work()
                runOnUiThread { success(result) }
            } catch (error: Exception) {
                runOnUiThread {
                    updateStatus("Connection error")
                    AlertDialog.Builder(this).setTitle("Could not reach Radarr")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun <T> tmdbBackground(work: () -> T, success: (T) -> Unit, failure: () -> Unit = {}) {
        networkExecutor.execute {
            try {
                val result = work()
                runOnUiThread { success(result) }
            } catch (error: Exception) {
                runOnUiThread {
                    failure()
                    updateStatus("TMDB connection error")
                    AlertDialog.Builder(this).setTitle("Could not load TMDB")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun updateStatus(value: String) { status.text = value }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in the Android framework; retained for Android TV remote compatibility")
    override fun onBackPressed() {
        if (currentScreen == Screen.SETTINGS && !connected) {
            AlertDialog.Builder(this)
                .setTitle("Exit ArrPilot?")
                .setMessage("Working connection settings are required before ArrPilot can be used.")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Exit") { _, _ -> finishAndRemoveTask() }
                .show()
            return
        }
        if (currentScreen == Screen.RELEASES && temporaryMovie != null) {
            leaveReleasePreview(::returnFromReleases)
            return
        }
        if (currentScreen == Screen.RELEASES) {
            returnFromReleases()
            return
        }
        if (currentScreen == Screen.EXPLORE && explorePrevious != null) {
            val previous = explorePrevious ?: return
            explorePrevious = null
            exploreCategory = previous.category
            exploreTitle = previous.title
            explorePage = previous.page
            exploreTotalPages = previous.totalPages
            exploreMovies = previous.movies
            customFilterActive = previous.customFilter
            renderExplore()
            updateStatus("${exploreMovies.size} movies")
            return
        }
        if (currentScreen != Screen.SEARCH || searchHasResults) {
            showSearch()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Exit ArrPilot?")
            .setMessage("The next launch will reconnect to Radarr and start with a fresh search.")
            .setNegativeButton("Stay", null)
            .setPositiveButton("Exit") { _, _ ->
                adapter.replace(emptyList())
                finishAndRemoveTask()
            }
            .show()
    }

    private fun returnFromReleases() {
        if (releaseReturnScreen == Screen.EXPLORE) showExplore() else showSearch()
    }

    private enum class Screen { SEARCH, EXPLORE, QUEUE, RELEASES, SETTINGS }

    private data class ExploreSnapshot(
        val category: ExploreCategory,
        val title: String,
        val page: Int,
        val totalPages: Int,
        val movies: List<Movie>,
        val customFilter: CustomDiscoverFilter?
    )

    companion object {
        const val GRID_ID = 9107
        const val SEARCH_INPUT_ID = 9108
        const val SEARCH_NAV_ID = 9109
        const val QUEUE_NAV_ID = 9110
        const val PROFILE_NAV_ID = 9111
        const val EXPLORE_NAV_ID = 9112
        const val EXPLORE_CATEGORY_ID = 9113
        const val CUSTOM_CATEGORY_ID = 9120
        const val SETTINGS_NAV_ID = 9121
        const val SETTINGS_RADARR_URL_ID = 9122
        const val SETTINGS_RADARR_KEY_ID = 9123
        const val SETTINGS_TMDB_TOKEN_ID = 9124
        const val SETTINGS_TMDB_KEY_ID = 9125
        const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"
        const val PREVIEW_ID_KEY = "temporary_preview_movie_id"
        const val PREVIEW_TMDB_KEY = "temporary_preview_tmdb_id"
        const val CUSTOM_GENRES_KEY = "custom_discover_genres"
        const val CUSTOM_SORT_KEY = "custom_discover_sort"
        const val CUSTOM_RELEASE_KEY = "custom_discover_release"
        const val CUSTOM_PERIOD_KEY = "custom_discover_period"
        const val CUSTOM_VOTES_KEY = "custom_discover_votes"
        const val CUSTOM_MAX_VOTES_KEY = "custom_discover_max_votes"
        const val CUSTOM_RATING_KEY = "custom_discover_rating"
        const val CUSTOM_RESULTS_KEY = "custom_discover_results"
        const val CUSTOM_EXCLUDE_RADARR_KEY = "custom_discover_exclude_radarr"

        val MOVIE_GENRES = listOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
            80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
            14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
            10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western"
        )
    }
}
