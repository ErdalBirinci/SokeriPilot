# Contributing to SokeriPilot

Thanks for looking. This is a small, privacy-first Android app; the fastest way to help is a clear
bug report with the notification text that failed to parse.

## Ground rules

- **No telemetry, no analytics, no third-party SDKs.** A change that adds one will not be merged.
- **No data leaves the phone** unless the user configures their own Nightscout server or turns on
  the Health Connect export.
- **No dosing advice.** The app shows and reminds; it never recommends insulin or treatment.
- Keep the site dependency-free: `landing/` ships HTML, CSS and one script, with a strict CSP.

## Build and test

```bash
cd android
export JAVA_HOME="$PWD/../.toolchain/jdk"
export GRADLE_USER_HOME="$PWD/../.toolchain/gradle-home"
../.toolchain/gradle-8.9/bin/gradle :app:assembleDebug :app:testDebugUnitTest
```

The landing page and the release tooling need only Node:

```bash
node tools/security-check.mjs
node tools/release-check.mjs
BASE_URL=http://127.0.0.1:4183 node tools/smoke.mjs
```

`tools/verify-device.sh` installs the debug build on a connected phone and walks the first-run path.

## Adding a CGM app

Follow `docs/cgm-compatibility.md`. Every new format needs a fixture in `CgmNotificationParserTest`
or `XDripReadingMapperTest`; "it worked on my phone" is not a test.

## Commits and pull requests

- One logical change per commit; explain *why* in the body.
- Run the tests you touched; say in the PR what you ran and what you could not run (no device, no
  car) instead of claiming it works.
- Never commit a keystore, `keystore.properties`, `local.properties`, `.env` or screenshots with
  personal data. `.gitignore` covers the common cases; check `git status` before pushing.

## License

MIT — see [LICENSE](LICENSE). By contributing you agree your changes are released under it.
