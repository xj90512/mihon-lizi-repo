# Working on this extension

- Preserve the package, source name, language and source ID unless explicitly migrating user libraries.
- `index.min.json` is a Tachimanga store: its `apk` field intentionally names a native JVM `.jar`.
- Keep JVM target 1.8, Android minSdk 21, minification off and host runtime dependencies compileOnly.
- Account Authorization belongs only on the HTTPS chapter endpoint. Never retry quota-consuming chapters or move account requests to HTTP/CDN hosts.
- Do not infer chapter image URLs or bypass login, time or account quota restrictions.
- Validate with `./gradlew testDebugUnitTest assembleRelease createReleaseExtensionJar` and `python3 scripts/verify_package.py --build-only`.
- Before publishing, use the maintained external signing key, run `scripts/publish.py`, then `scripts/verify_package.py`; regenerate the index, checksums and both artifacts together.
- Keep signing keys, account tokens, captures and machine-specific configuration out of Git.
- Phone compatibility requires a Tachimanga device check; compilation and desktop-host success do not establish it.
