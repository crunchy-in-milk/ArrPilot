# Changelog

All notable changes to ArrPilot will be documented in this file.

The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.1] - 2026-10-04

### Navigation and focus

- Prevent Back from looping between a person filmography, cast list, and movie overview by restoring the original movie's return destination
- Remember movie focus independently for Search, Explore, and person filmographies so nested browsing cannot overwrite or consume another screen's saved card
- Restore the exact cast member after returning from a person filmography
- Delay and retry grid focus restoration until responsive card layout is complete, with a safe fallback when the saved movie is no longer present

### Custom discovery

- Add a persistent “Has trailer” filter that can limit Custom Explore to movies with a playable YouTube trailer
- Reuse the movie overview's trailer-selection logic so every filtered result offers the same Trailer action when opened

## [0.2.0] - 2026-09-28

### Android TV experience

- Rebuilt the visual language with a darker opaque gradient background, refined surfaces, clearer pink focus treatment, rounded cards, and updated system colors
- Redesigned the top navigation with dedicated Search, Explore, Queue, Profile, and Settings icons, selected-tab styling, and TV-remote focus states
- Refined header layout so breadcrumb titles and status text stay readable without overlapping or clipping
- Updated movie cards with cropped poster artwork, cleaner framing, and enough text space for two-line titles plus year and library status

### Movie, cast, and people browsing

- Replaced the basic movie dialog with a full movie overview containing poster art, year, certification, runtime, TMDB score and vote count, genres, library state, and synopsis
- Added a unified movie-action row for Cast, Trailer, related movies, collections, and Radarr/release actions; Back now closes the overview instead of needing a separate Close button
- Added cast browsing with portrait cards, role names, initials fallbacks for missing portraits, and responsive TV grid sizing
- Added person filmographies from cast members, ordered newest first and loaded in manageable pages
- Added related-movie and collection browsing paths that retain their correct return destination
- Refresh movie overview state immediately after adding a title to Radarr

### Exploration controls

- Show the loaded and available-result counts while browsing Explore and Custom Explore pages
- Add a Clear filters action that resets Custom Explore to its defaults and reloads results
- Add an optional “Likely short films” exclusion for Custom Explore; it removes only titles with a confirmed runtime under 20 minutes while retaining titles whose runtime is not available
- Persist the short-film preference alongside existing Custom Explore settings

### Navigation and reliability

- Restore focus to the exact movie card after returning from a movie overview across Explore, Search, related movies, collections, and person filmographies
- Preserve Search queries and loaded results when returning from a movie overview
- Restore focus to the exact cast member after returning from that person’s filmography
- Make Back navigation consistent across movie details, cast, people, release previews, and Explore subviews
- Hide redundant movie-title status text in the overview header
- Prevent movie-card metadata, including the year, from being cut off when a title wraps

## [0.1.1] - 2026-09-19

### Changed

- Custom discovery now offers an unlimited result option while continuing to load TMDB pages only as you browse
- Removed the hard-coded discovery styles so Custom results are determined solely by the filters you choose
- Made Radarr options available for every movie that is not already in Radarr
- Simplified the Settings and Profile controls

### Fixed

- Prevented malformed TMDB metadata for one optional detail section from blocking a movie's full detail view


## [0.1.0] - 2026-09-16

### Added

- GitHub release checks, approved downloads, APK identity verification, and Android-confirmed installation

- Remote-friendly Android TV interface for Radarr movie search and queue viewing
- Interactive Radarr release preview and explicit release selection
- Monitored and unmonitored movie additions with profile and root-folder selection
- TMDB-powered discovery, recommendations, collections, trailers, and custom filters
- Encrypted on-device connection settings
- Adaptive movie grids and bounded artwork loading for TV displays
