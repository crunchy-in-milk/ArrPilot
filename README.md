# ArrPilot

ArrPilot is a lightweight, remote-friendly Android TV frontend for Radarr. It focuses on finding movies from the couch, reviewing the exact releases Radarr can see, and choosing what gets downloaded.

ArrPilot is an independent, unofficial project. It is not affiliated with or endorsed by Radarr.

## Features

- Search Radarr's movie lookup service with title-aware relevance ranking
- Browse trending, popular, theatrical, upcoming, and custom TMDB discovery results
- Discover collections, recommendations, genres, multiple decades, release windows, rating thresholds, and more
- Add movies to Radarr as monitored or unmonitored with a chosen quality profile and root folder
- Preview Radarr releases without permanently keeping the movie in the library
- Review release date, quality, size, indexer, seeders, custom-format score, and rejection reasons
- Require confirmation only after choosing an exact eligible release
- Open trailers in the signed-in YouTube TV app
- View active Radarr downloads in the queue
- Navigate the complete interface with a D-pad remote
- Adapt card sizing, grid columns, and focus-safe gutters to different TV resolutions

## Requirements

- Android TV or Google TV running Android 8.0 (API 26) or newer
- A reachable Radarr server and API key
- A TMDB API Read Access Token or v3 API key for discovery features
- YouTube TV is optional and used only when opening trailers

## Setup

1. Install the ArrPilot APK on the Android TV device.
2. Open **Settings** in ArrPilot.
3. Enter the full Radarr URL, including `http://` or `https://`, and the Radarr API key.
4. Enter either a TMDB API Read Access Token (recommended) or a TMDB v3 API key.
5. Select **Save & connect**, then choose the desired Radarr quality profile and root folder.

Connection details are encrypted with an Android Keystore key and kept in the app's private storage. They are never compiled into the APK or sent to an ArrPilot-operated service.

HTTPS is strongly recommended whenever Radarr is reachable outside a trusted home network. ArrPilot permits cleartext HTTP because many local Radarr installations use an HTTP address on a private LAN.

## Updates and installation

Download `ArrPilot-0.2.1.apk` from [GitHub Releases](https://github.com/crunchy-in-milk/ArrPilot/releases). Transfer it to the TV and open it with an APK installer, or use `adb install -r ArrPilot-0.2.1.apk` from a connected computer.

ArrPilot checks the latest stable GitHub release on launch, at most once every six hours. **Settings → Check for updates** always checks immediately. Downloads begin only after you choose **Download**. The app verifies the APK package, version code, and signing certificate before opening Android's installation confirmation. Android may require a one-time permission to install from ArrPilot. Settings are retained during updates.

Maintainers: increment both `versionName` and `versionCode` for each release. Publish a stable GitHub Release tagged `vX.Y.Z` with a permanently signed asset named `ArrPilot-X.Y.Z.apk`. Drafts and prereleases are not offered. The CI debug APK is for development and is not an official update.

## Building from source

Prerequisites:

- JDK 17 or newer
- Android SDK Platform 36
- A configured Android SDK path, normally supplied by Android Studio or a local `local.properties` file

On Linux or macOS:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Official releases are signed with ArrPilot's permanent release key. Local signing credentials belong in the ignored `release-signing.properties` file. Automated builds can instead provide `ARRPILOT_KEYSTORE_FILE`, `ARRPILOT_KEYSTORE_PASSWORD`, `ARRPILOT_KEY_ALIAS`, and `ARRPILOT_KEY_PASSWORD`. Release builds fail when signing is absent so an unsigned APK cannot be published accidentally.

The expected public signing-certificate fingerprint is documented in [SIGNING-CERTIFICATE.md](SIGNING-CERTIFICATE.md).

## Privacy and security

ArrPilot has no analytics, advertising, user accounts, or hosted backend. See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) for details.

Never include Radarr or TMDB credentials in bug reports, screenshots, logs, commits, or GitHub Actions configuration.

## TMDB attribution

Movie discovery data and images are provided by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.

The TMDB logo displayed in ArrPilot's About screen is an approved TMDB attribution mark and remains the property of TMDB.

## License

ArrPilot is licensed under the [GNU General Public License v3.0](LICENSE).
