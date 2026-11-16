# No in-app updater or network client

The Chinese edition of Spektrafilm for Android is fully local. There is **no updater**, no HTTP
client, and no `INTERNET` permission. The app therefore cannot check for, download, or install an
update on its own, and it cannot upload a photo or any other data.

GitHub, source, issue, licence and attribution links are ordinary `Intent.ACTION_VIEW` handoffs.
The user's browser (or another user-chosen handler) performs the request; the app does not read the
response and does not fetch the page in-process. The surrounding UI states that the link opens in the
browser.

## Integrity boundary

Because there is no in-app download flow, the app makes no claim to verify APK bytes, checksums, a
release signer, or release metadata. Android's package manager enforces signing-certificate
continuity for an upgrade, and the browser/distribution channel is responsible for first-install
provenance. The app does not request `REQUEST_INSTALL_PACKAGES`.

If a future release adds any network operation, that is a new privacy and security design: the code,
manifest, in-app disclosure, [PRIVACY.md](PRIVACY.md), tests, and store disclosures must change
together. A versioned canonical metadata format, an application-trusted offline verification key,
exact digest/byte limits, package/signing checks, and hostile-parser tests would be required before
trusting a downloaded artifact.

## Verification

- `OfflinePrivacyTest` inspects the main manifest and sources for: no `INTERNET` permission, no
  network security configuration, no `AppUpdater`/HTTP client/upload path, no account, analytics,
  advertising or API-key dependency, and browser-only external links.
- `BackupSecurityConfigTest` still pins the narrow backup allowlist, `allowBackup=false`,
  `restoreAnyVersion=false`, and the required foreground-export permissions while rejecting
  `INTERNET` and other privileged access.
- `LocalizationResourcesTest` keeps the English and Chinese resources key/order/placeholder-identical.
