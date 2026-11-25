# Releasing GlucoPilot

`landing/releases.json` is the single description of a release. The page, the archive page,
`landing/_headers`, every tool and the live check all read it, and `tools/release-check.mjs`
proves they agree with the APK bytes. Do not bump a version anywhere else by hand.

## 1. Build and sign

```bash
cd android
export JAVA_HOME="$PWD/../.toolchain/jdk"
export GRADLE_USER_HOME="$PWD/../.toolchain/gradle-home"
export ANDROID_HOME="$(cygpath -m "$PWD/../.toolchain/android-sdk")"
../.toolchain/gradle-8.9/bin/gradle :app:assembleRelease :app:testDebugUnitTest
```

`android/app/build.gradle.kts` reads `android/keystore.properties`; without it the release build is
unsigned and cannot be installed over the previous version.

## 2. Read the APK's own facts

```bash
BT=.toolchain/android-sdk/build-tools/35.0.0
cp android/app/build/outputs/apk/release/app-release.apk landing/download/GlucoPilot-<version>.apk
"$BT/aapt2.exe" dump badging landing/download/GlucoPilot-<version>.apk | head -1
sha256sum landing/download/GlucoPilot-<version>.apk
stat -c %s landing/download/GlucoPilot-<version>.apk
JAVA_HOME="$PWD/.toolchain/jdk" "$BT/apksigner.bat" verify --print-certs landing/download/GlucoPilot-<version>.apk
```

The `versionName` must equal the new version, and the certificate SHA-256 must equal the one already
recorded in `releases.json`. A different certificate means the update cannot install over 1.1.3 or
1.2.0 — restore the release key from `backups/` instead of shipping it.

## 3. Update the manifest, then the things it feeds

Add an entry to `landing/releases.json` (`version`, `versionCode`, `date`, `file`, `size`, `sha256`,
`certSha256`), move `current`, and bump `assetStamp` to `<version>-1`. Then:

- `landing/index.html` — announcement tag, download links, version/size rows, SHA-256 and certificate
- `landing/_headers` — one block per APK; never a `/download/*.apk` wildcard, which would rename
  archived builds
- `landing/download/index.html` — the archive row, and the "Latest" flag
- every stylesheet link and the archive page move to the new `?v=<assetStamp>`

## 4. Check locally

```bash
node tools/release-check.mjs
node tools/security-check.mjs
python -m http.server 4173 --bind 127.0.0.1 --directory landing &
node tools/smoke.mjs
```

`release-check` fails if the page hash, size, links, headers, archive or asset stamp disagree with
`releases.json`, or if the APK's versionName or certificate differ from the manifest.

## 5. Preview before production

Deploy to the `preview` environment first, then run the live checks against it:

```bash
BASE_URL=https://glucopilot-civgdk-preview.freebuff.site node tools/live-check.mjs
```

The live check compares the served bytes with the local APK, checks that each stylesheet response is
fresh at the current `?v=` stamp (a cached document pairing with an old stylesheet is the failure
this catches), and verifies the archive, the manifest and the QR asset.

## 6. Production

Deploy, then:

```bash
node tools/live-check.mjs
```

Edge caches can serve the previous document for a few seconds after a deploy; re-run the check
rather than assuming success from the deploy receipt.

## 7. Tag and commit

```bash
git add -A
git commit -m "Release <version>"
git tag -a v<version> -m "GlucoPilot <version>"
```

The tag is what ties the published APK hash to the source that produced it. `v1.2.0` is the commit
that was live with 1.2.0.

## Rollback

Older signed APKs stay in `landing/download/` and in the archive page, so a downgrade is a normal
install over the newer version (no uninstall, no lost settings — Android allows it because the
certificate is the same). To roll the site back, check out the release tag and deploy again:

```bash
git checkout v1.2.0
# deploy production from this workspace, then:
node tools/live-check.mjs
```

Database and secrets are not touched by a deploy, so rolling the site back never deletes anything.
