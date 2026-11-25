# Releasing SokeriPilot

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
unsigned and cannot be installed over the previous version. The file also holds the two identities
the update path depends on: keep `applicationId` (`app.glcopilot`) and the signing key unchanged, and
raise `versionCode` together with `versionName`. The namespace (`app.sokeripilot`) only names the
code.

## 2. Read the APK's own facts

```bash
BT=.toolchain/android-sdk/build-tools/35.0.0
cp android/app/build/outputs/apk/release/app-release.apk landing/download/SokeriPilot-<version>.apk
"$BT/aapt2.exe" dump badging landing/download/SokeriPilot-<version>.apk | head -1
sha256sum landing/download/SokeriPilot-<version>.apk
stat -c %s landing/download/SokeriPilot-<version>.apk
JAVA_HOME="$PWD/.toolchain/jdk" "$BT/apksigner.bat" verify --print-certs landing/download/SokeriPilot-<version>.apk
```

The `versionName` must equal the new version, and the certificate SHA-256 must equal the one already
recorded in `releases.json`. A different certificate means the update cannot install over 1.1.3 or
1.2.0 or 1.2.1 — restore the release key from `backups/` instead of shipping it.

## 3. Update the manifest, then the things it feeds

Let the tool do the mechanical part; it reads size, SHA-256 and (with the portable SDK) the APK's
versionName and certificate, and refuses a signer that differs from the published one:

```bash
node tools/release.mjs --apk landing/download/SokeriPilot-<version>.apk          # dry run
node tools/release.mjs --apk landing/download/SokeriPilot-<version>.apk --write  # apply
```

It writes the manifest entry, moves `current`, sets `assetStamp` to `<version>-1`, and updates:

- `landing/index.html` — announcement tag, download links, version/size rows, SHA-256, certificate, JSON-LD
- `landing/_headers` — one block per APK; never a `/download/*.apk` wildcard, which renames archived builds
- `landing/download/index.html` — the new archive row and the "Latest" flag
- `landing/install/index.html` — file name, size and hash prefix
- every stylesheet link on all five pages (`?v=<assetStamp>`), including the install guide, privacy page and 404

Still by hand: the announcement sentence, the release-notes section and any copy that describes the
new features. Running the tool for the version that is already current is a no-op, which is what CI
checks.

## 4. Check locally

```bash
node tools/release-check.mjs
node tools/security-check.mjs
python -m http.server 4173 --bind 127.0.0.1 --directory landing &
node tools/smoke.mjs
```

`release-check` fails if the page hash, size, links, headers, archive, install guide or asset stamp
disagree with `releases.json`, or if the APK's versionName or certificate differ from the manifest.
`security-check` additionally proves the HTTP security headers, the strict CSP on every page, the
JSON-LD block and that `robots.txt`/`sitemap.xml` use the production origin.

## 5. Preview before production

Deploy to the `preview` environment first, then run the live checks against it:

```bash
BASE_URL=https://glucopilot-civgdk-preview.freebuff.site node tools/live-check.mjs
```

The live check compares the served bytes with the local APK, checks that each stylesheet response is
fresh at the current `?v=` stamp (a cached document pairing with an old stylesheet is the failure
this catches), and verifies the archive, the manifest, the QR asset, the security headers, the
install and privacy pages with the crawler files, and that an unknown path answers with the branded
404 page.

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
git tag -a v<version> -m "SokeriPilot <version>"
```

After any change to the signing key or its backup, prove the backup still restores:

```bash
KEYSTORE_PASSPHRASE='…' bash tools/keystore-drill.sh
```

For unattended monitoring, schedule `node tools/watch.mjs` (it writes `dist/watch.json` and posts to
`WATCH_WEBHOOK` when set, so cron exits non-zero on a failing site).

The tag is what ties the published APK hash to the source that produced it. `v1.2.0` and `v1.2.1`
are the commits that were live with those releases.

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

## GitHub Pages mirror

Every push to `master` that touches `landing/` also publishes a mirror through
`.github/workflows/pages.yml`. GitHub Pages serves a project site from a subpath, so the workflow
runs `node tools/pages-build.mjs _site`, which copies the same files, drops the Freebuff-specific
`_headers`, adds the `/SokeriPilot/` prefix to the four root-absolute links and re-states the header
policy as a meta tag (without `frame-ancestors`, which a meta policy cannot express). The source
stays root-absolute for the primary site; only the published copy is rewritten. Run the same script
locally to see exactly what Pages will get.
