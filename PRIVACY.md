# Privacy

ArrPilot does not operate a backend service and does not collect analytics, advertising identifiers, telemetry, or user-account information.

## Information stored on the device

ArrPilot stores the following in the application's private Android storage:

- The Radarr URL and API key entered by the user
- The TMDB API Read Access Token or v3 API key entered by the user
- The selected Radarr quality profile and root folder
- Local interface preferences needed to restore the user's choices

Connection credentials are encrypted using the Android Keystore. Uninstalling ArrPilot removes its private application data.

## Network requests

ArrPilot communicates directly from the Android TV device with:

- The user-configured Radarr server
- TMDB when discovery features, metadata, or artwork are requested
- YouTube TV when the user explicitly chooses to open a trailer
- GitHub to check for a newer public ArrPilot release (at most once per six hours on launch, or when requested in Settings), and to download an APK after approval. These requests do not include Radarr or TMDB credentials.

No credentials or usage information are sent to an ArrPilot-operated server.

Users control the Radarr and TMDB credentials they provide and are responsible for the privacy and security of those services. HTTPS should be used for any Radarr server available outside a trusted private network.

## Changes

Material changes to this policy will be documented in the project changelog.
