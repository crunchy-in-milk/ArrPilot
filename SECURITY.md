# Security policy

## Reporting a vulnerability

Please do not disclose a suspected vulnerability in a public issue.

Use GitHub's private vulnerability reporting feature for the `crunchy-in-milk/ArrPilot` repository. Include the affected ArrPilot version, reproduction steps, expected impact, and any suggested mitigation. Remove all Radarr URLs, API keys, TMDB credentials, and other private information before submitting the report.

Security reports will be reviewed before public disclosure. A fix and disclosure timeline will depend on severity and reproducibility.

## Credential safety

ArrPilot credentials must never be committed to the repository or included in screenshots, logs, build artifacts, issues, or workflow files. Local signing keys and signing passwords are explicitly excluded from version control.

Only APKs signed with the certificate fingerprint documented in [SIGNING-CERTIFICATE.md](SIGNING-CERTIFICATE.md) should be treated as official ArrPilot builds.
