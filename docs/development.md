# Development

## Requirements

- JDK 17 or newer (CI uses 21)
- Android SDK with platform 36 and build-tools 36.0.0
- `local.properties` with `sdk.dir=/path/to/Android/Sdk` (git-ignored)

## Build

```sh
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest      # JVM unit tests
./gradlew lintRelease              # Android lint
```

Release builds are minified and resource-shrunk. Without a signing key they are signed with the
debug key, so a fresh checkout always builds.

### Signing

The release key is read from environment variables, or from an env-style file passed as a Gradle
property:

```sh
./gradlew assembleRelease -Pcouch.signing=/path/to/couch-release.env
```

```
COUCH_KEYSTORE=/path/to/couch-release.jks
COUCH_KEYSTORE_PASSWORD=...
COUCH_KEY_ALIAS=couch
COUCH_KEY_PASSWORD=...
```

An APK signed with a different key can't update an installed one: Android requires an uninstall
first, which deletes Couch's settings. Back them up first (see
[configuration](configuration.md#configjson)).

### Version

The version comes from the latest `vX.Y.Z` tag: `versionName` is `X.Y.Z` and `versionCode` is
`X·10000 + Y·100 + Z`. Without tags it is `0.1.0`.

## Running on a TV emulator

An Android 12 TV image at 1080p and 320 dpi matches a typical 2020 TV:

```sh
sdkmanager "emulator" "system-images;android-31;android-tv;x86"
avdmanager create avd -n couch-tv -k "system-images;android-31;android-tv;x86" -d tv_1080p
emulator -avd couch-tv -no-window -no-audio -gpu swiftshader_indirect -port 5580 &
adb -s emulator-5580 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5580 shell pm grant cloud.samlo.couch android.permission.READ_TV_LISTINGS
adb -s emulator-5580 shell am start -n cloud.samlo.couch/.MainActivity
adb -s emulator-5580 exec-out screencap -p > shot.png
```

The emulator has no HDMI inputs, so the inputs row only shows on a real TV.

## Measuring smoothness

```sh
adb shell dumpsys gfxinfo cloud.samlo.couch reset
# navigate for a while
adb shell dumpsys gfxinfo cloud.samlo.couch | grep -E "Janky|percentile"
```

Frame times above 16.7 ms drop a frame at 60 Hz. Check the 90th and 99th percentiles after any UI
change.

## Weather icons

The `wx_*` drawables are Meteocons (MIT) turned into animated vectors by a script; regenerate
them with `bun scripts/meteocons.ts` instead of editing them.

## Documentation site

The pages in `docs/` are a [VitePress](https://vitepress.dev) site:

```sh
bun install
bun run docs:dev        # live preview
bun run docs:build      # docs/.vitepress/dist
```

The `Docs` workflow builds it on every push to `main` that touches `docs/` and publishes it.

## CI and releases

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request: build,
  unit tests, lint, a 600 KB APK size budget, and the APK as an artifact.
- **Release** (`.github/workflows/release.yml`) runs on `v*` tags: it builds and signs the APK and
  publishes a GitHub release with the APK and its SHA-256.
- **Docs** (`.github/workflows/docs.yml`) builds the documentation site and pushes it to the
  repository that serves it. Android CI skips pushes that only change documentation.

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The release workflow needs four repository secrets: `COUCH_KEYSTORE_BASE64` (the keystore,
base64-encoded), `COUCH_KEYSTORE_PASSWORD`, `COUCH_KEY_ALIAS` and `COUCH_KEY_PASSWORD`.
