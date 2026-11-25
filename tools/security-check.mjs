#!/usr/bin/env node
/**
 * SokeriPilot static security and code checks.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/security-check.mjs
 */
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = process.cwd();
const failures = [];
let passed = 0;

function pass(name, detail = "") {
  passed += 1;
  console.log(`PASS  ${name}${detail ? " — " + detail : ""}`);
}

function fail(name, reason) {
  failures.push(name);
  console.log(`FAIL  ${name} — ${reason}`);
}

function check(name, fn) {
  try {
    const detail = fn();
    pass(name, detail || "");
  } catch (error) {
    fail(name, error.message);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function read(path) {
  return readFileSync(join(ROOT, path), "utf8");
}

function exists(path) {
  return existsSync(join(ROOT, path));
}

function walk(dir, acc = []) {
  const absolute = join(ROOT, dir);
  if (!existsSync(absolute)) return acc;
  for (const entry of readdirSync(absolute)) {
    const full = join(absolute, entry);
    if (statSync(full).isDirectory()) walk(join(dir, entry), acc);
    else acc.push(relative(ROOT, full).split("\\").join("/"));
  }
  return acc;
}

const manifestPath = "android/app/src/main/AndroidManifest.xml";
const manifest = read(manifestPath);
const mainSources = walk("android/app/src/main/java").filter((file) => file.endsWith(".kt"));
const landingFiles = [
  "landing/index.html",
  "landing/install/index.html",
  "landing/privacy/index.html",
  "landing/404.html",
  "landing/download/index.html",
  "landing/styles.css",
  "landing/showcase.css",
  "landing/signals.css",
  "landing/app.js"
];

/* ------------------------------------------------------------------ */
/* Android manifest                                                    */
/* ------------------------------------------------------------------ */

check("manifest: cleartext traffic disabled", () => {
  assert(manifest.includes('android:usesCleartextTraffic="false"'), "usesCleartextTraffic=false missing");
  assert(manifest.includes('android:networkSecurityConfig="@xml/network_security_config"'), "networkSecurityConfig missing");
  assert(read("android/app/src/main/res/xml/network_security_config.xml").includes('cleartextTrafficPermitted="false"'), "cleartextTrafficPermitted=false missing");
  return "usesCleartextTraffic=false + network security config";
});

check("manifest: excluded from backup", () => {
  assert(manifest.includes('android:allowBackup="false"'), "allowBackup=false missing");
  assert(exists("android/app/src/main/res/xml/data_extraction_rules.xml"), "data_extraction_rules.xml missing");
  return "allowBackup=false";
});

check("manifest: only expected components exported", () => {
  const blocks = manifest.match(/<(activity|service|receiver|provider)[\s\S]*?(\/?)>/g) ?? [];
  const allowed = [
    "MainActivity",
    "SokeriPilotCarAppService",
    "XDripBroadcastReceiver",
    "CgmNotificationListener"
  ];
  const exported = blocks.filter((block) => block.includes('android:exported="true"'));
  for (const block of exported) {
    assert(allowed.some((name) => block.includes(name)), `unexpected exported component: ${block.slice(0, 80)}`);
  }
  assert(exported.length === 4, `exported component count is not 4: ${exported.length}`);
  return `${exported.length} intentional exports`;
});

check("manifest: least-privilege permissions", () => {
  const permissions = [...manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"/g)].map((m) => m[1]);
  const allowed = [
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.REQUEST_REBIND_NOTIFICATION_LISTENER_SERVICE",
    "com.eveningoutpost.dexdrip.permissions.RECEIVE_BG_ESTIMATE",
    // Optional, user-granted Health Connect export; the app never reads health data back.
    "android.permission.health.WRITE_BLOOD_GLUCOSE"
  ];
  assert(permissions.length === 7, `permission count is not 7: ${permissions.length}`);
  for (const permission of permissions) {
    assert(allowed.includes(permission), `unexpected permission: ${permission}`);
  }
  return permissions.length + " permissions";
});

check("manifest: CarAppService category is correct", () => {
  assert(manifest.includes("androidx.car.app.category.IOT"), "IOT category missing");
  assert(manifest.includes('android:name="com.google.android.gms.car.application"'), "car metadata missing");
  assert(read("android/app/src/main/res/xml/automotive_app_desc.xml").includes('name="template"'), "automotive_app_desc template missing");
  return "templated IOT app";
});

check("background: driving monitor service is restricted", () => {
  assert(manifest.includes('android:foregroundServiceType="specialUse"'), "FGS type is not specialUse");
  assert(manifest.includes("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"), "specialUse subtype declaration missing");
  const service = /<service[\s\S]*?GlucoseMonitorService[\s\S]*?>/.exec(manifest)?.[0] ?? "";
  assert(service.includes('android:exported="false"'), "monitor service is exported");
  assert(!exists("android/app/src/main/java/app/sokeripilot/monitor/BootReceiver.kt"), "a boot receiver still starts the service");
  assert(!manifest.includes("BOOT_COMPLETED"), "boot start is still declared in the manifest");
  const monitor = read("android/app/src/main/java/app/sokeripilot/monitor/GlucoseMonitorService.kt");
  assert(monitor.includes("CarConnectionWatcher.observe"), "service does not observe the car connection");
  assert(monitor.includes("START_NOT_STICKY"), "service can be resurrected while unplugged");
  assert(!manifest.includes("SYSTEM_ALERT_WINDOW"), "overlay permission is present");
  return "specialUse FGS, car-bound start/stop, exported=false, no overlay";
});

check("car: monitoring on launch and start warning", () => {
  const screen = read("android/app/src/main/java/app/sokeripilot/car/GlucoseScreen.kt");
  const launcher = read("android/app/src/main/java/app/sokeripilot/monitor/MonitorLauncher.kt");
  const activity = read("android/app/src/main/java/app/sokeripilot/phone/MainActivity.kt");
  assert(screen.includes("MonitorLauncher.start"), "car screen does not start monitoring");
  assert(screen.includes("car_notify_permission"), "notification permission warning missing");
  assert(screen.includes("car_monitor_off"), "driving mode warning missing");
  assert(screen.includes("car_service_failed"), "service start failure warning missing");
  assert(screen.includes("car_source_missing"), "missing source warning absent");
  assert(launcher.includes("startForegroundService"), "foreground service start missing");
  assert(launcher.includes("BLOCKED"), "start result is not reported");
  assert(activity.includes("MonitorLauncher.start"), "phone does not use the same launcher");
  return "car + phone share one launcher; permission/source/block warnings";
});

check("CGM: automatic detection is narrowly scoped", () => {
  assert(!manifest.includes("android.permission.QUERY_ALL_PACKAGES"), "broad permission for all packages is present");
  assert(manifest.includes("<queries>") && manifest.includes("com.eveningoutpost.dexdrip"), "CGM package query missing");
  assert(manifest.includes("tk.glucodata"), "Juggluco query missing");
  const detector = read("android/app/src/main/java/app/sokeripilot/data/CgmAppDetector.kt");
  assert(detector.includes("getPackageInfo"), "installed-app detection missing");
  assert(detector.includes("CgmAppCatalog"), "testable catalog is not used");
  assert(manifest.includes("com.freestylelibre.app.tr"), "Libre TR package query missing");
  assert(manifest.includes("com.freestylelibre3.app.us"), "Libre 3 package query missing");
  const app = read("android/app/src/main/java/app/sokeripilot/SokeriPilotApp.kt");
  assert(app.includes("autoEnableXDrip"), "automatic listening enablement missing");
  const activity = read("android/app/src/main/java/app/sokeripilot/phone/MainActivity.kt");
  assert(activity.includes("renderCgmDetection"), "detection status is not shown on the phone");
  return "queries + auto-listening + status text";
});

check("CGM: Libre and Diabox classification tested", () => {
  const catalog = read("android/app/src/main/java/app/sokeripilot/data/CgmAppCatalog.kt");
  assert(catalog.includes("com.freestylelibre.app.tr") && catalog.includes("NOTIFICATION"), "Libre TR is not classified as notification-readable");
  assert(!catalog.includes("BRIDGE_REQUIRED"), "stale bridge-required classification is still present");
  assert(!/Diabox[\s\S]{0,80}XDRIP_BROADCAST/.test(catalog), "Diabox was treated as an xDrip broadcast without verification");
  const test = read("android/app/src/test/java/app/sokeripilot/data/CgmAppCatalogTest.kt");
  assert(test.includes("FreeStyle LibreLink (TR)"), "Libre detection test missing");
  assert(test.includes("verified broadcast source"), "Diabox claim is not tested");
  return "Libre/Dexcom notification-readable, Diabox and xDrip broadcast sources are tested";
});

check("CGM: notification access is the only extra grant and data stays local", () => {
  const manifest = read("android/app/src/main/AndroidManifest.xml");
  assert(
    manifest.includes("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
    "notification listener service is not bound with the system permission"
  );
  assert(
    !manifest.includes('uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"'),
    "broad package visibility was added"
  );
  const listener = read("android/app/src/main/java/app/sokeripilot/data/CgmNotificationListener.kt");
  assert(listener.includes("sbn.packageName == packageName"), "SokeriPilot's own notification is not filtered out");
  const parser = read("android/app/src/main/java/app/sokeripilot/data/CgmNotificationParser.kt");
  assert(parser.includes("MAX_MGDL"), "notification values are not range validated");
  const test = read("android/app/src/test/java/app/sokeripilot/data/CgmNotificationParserTest.kt");
  assert(test.includes("unitless value from an unrelated app is rejected"), "parser rejection is not tested");
  return "system-bound listener, self-filter, range validation and parser tests";
});

check("distribution: signed release and outputs outside the repository", () => {
  const ignore = read(".gitignore");
  assert(/^dist\/$/m.test(ignore), "dist/ is not in gitignore");
  assert(ignore.includes("keystore.properties"), "keystore.properties is not in gitignore");
  assert(ignore.includes("*.jks") || ignore.includes("*.keystore"), "keystore file is not in gitignore");
  const gradle = read("android/app/build.gradle.kts");
  assert(gradle.includes("keystore.properties"), "release signing configuration missing");
  assert(gradle.includes("signingConfig = signingConfigs.getByName(\"release\")"), "release signingConfig is not attached");
  return "dist/ and keystore outside git; release signing attached";
});

check("device: single-command verification script", () => {
  const script = read("tools/verify-device.sh");
  assert(script.includes("adb") || script.includes("$ADB"), "adb usage missing");
  assert(script.includes("install -r -g"), "APK installation missing");
  assert(script.includes("pm grant"), "notification permission grant missing");
  assert(script.includes("dumpsys notification"), "notification check missing");
  assert(script.includes("dumpsys activity services"), "service check missing");
  assert(script.includes("GlucoseMonitorService"), "service name is not checked");
  return "install + permission + notification/service status";
});

check("car: notification and cockpit shortcut while navigation is open", () => {
  const notifier = read("android/app/src/main/java/app/sokeripilot/notify/GlucoseNotifier.kt");
  assert(notifier.includes("CarAppExtender"), "car notification (CarAppExtender) missing");
  assert(notifier.includes("CarPendingIntent.getCarApp"), "one-tap cockpit shortcut missing");
  assert(notifier.includes("IMPORTANCE_LOW"), "silent status notification missing");
  assert(notifier.includes("IMPORTANCE_HIGH"), "out-of-range alert priority missing");
  assert(notifier.includes("MuteAlertsReceiver"), "mute action missing");
  return "silent status + high-priority alert + cockpit";
});

check("car: host validation is restricted", () => {
  const service = read("android/app/src/main/java/app/sokeripilot/car/SokeriPilotCarAppService.kt");
  assert(service.includes("createHostValidator"), "createHostValidator missing");
  assert(service.includes("FLAG_DEBUGGABLE"), "debug/release split missing");
  assert(service.includes("hosts_allowlist_sample"), "release allowlist missing");
  const releaseBranch = service.split("else {")[1] ?? "";
  assert(!releaseBranch.includes("ALLOW_ALL_HOSTS_VALIDATOR"), "release build allows all hosts");
  return "debug: allow-all, release: Google host allowlist";
});

/* ------------------------------------------------------------------ */
/* Network hardening                                                   */
/* ------------------------------------------------------------------ */

const client = read("android/app/src/main/java/app/sokeripilot/data/NightscoutClient.kt");
const security = read("android/app/src/main/java/app/sokeripilot/data/NightscoutConfig.kt");
const settings = read("android/app/src/main/java/app/sokeripilot/data/SettingsRepository.kt");

check("network: redirects are rejected", () => {
  assert(client.includes("instanceFollowRedirects = false"), "instanceFollowRedirects=false missing");
  assert(client.includes("code in 300..399"), "3xx rejection missing");
  return "3xx rejected";
});

check("network: response size is limited", () => {
  assert(client.includes("maxResponseBytes"), "maxResponseBytes missing");
  assert(client.includes("total > limit"), "size check missing");
  return "512 KB cap";
});

check("network: HTTPS only", () => {
  const httpLiterals = mainSources
    .flatMap((file) => read(file).split("\n").map((line, i) => ({ file, line, i })))
    .filter((row) => /http:\/\//.test(row.line));
  assert(httpLiterals.length === 0, `http:// literal: ${httpLiterals.map((r) => r.file).join(", ")}`);
  assert(security.includes('equals("https", ignoreCase = true)'), "https enforcement missing");
  return "no http literal";
});

check("network: strict URL validation", () => {
  assert(security.includes("userInfo"), "userinfo rejection missing");
  assert(security.includes("isNullOrEmpty() || !uri.fragment"), "query/fragment rejection missing");
  assert(security.includes('it == ".."'), "path traversal check missing");
  return "userinfo/query/fragment/traversal";
});

/* ------------------------------------------------------------------ */
/* Secret handling                                                     */
/* ------------------------------------------------------------------ */

check("secret: raw value is not written to disk", () => {
  assert(settings.includes("NightscoutSecurity.sha1Hex(apiSecret)"), "sha1Hex is not used");
  assert(!/putString\(\s*KEY_SECRET\s*,\s*apiSecret/.test(settings), "raw key is written");
  assert(!settings.includes("putString(KEY_SECRET, baseUrl"), "URL is written as the key");
  return "SHA-1 digest only";
});

check("secret: never logged", () => {
  const logging = mainSources.filter((file) => /\b(Log\.[vdiew]|println\(|System\.out)/.test(read(file)));
  assert(logging.length === 0, `logging call: ${logging.join(", ")}`);
  return "no logging calls";
});

check("secret: not exposed in the UI", () => {
  const activity = read("android/app/src/main/java/app/sokeripilot/phone/MainActivity.kt");
  const layout = read("android/app/src/main/res/layout/activity_main.xml");
  assert(activity.includes('etSecret.setText("")'), "key field is not cleared");
  assert(layout.includes('android:inputType="textPassword"'), "password field is not masked");
  assert(layout.includes("importantForAutofill=\"no\""), "key field is open to autofill");
  return "masked field + autofill off + cleared after save";
});

check("data: incoming readings are validated", () => {
  const parser = read("android/app/src/main/java/app/sokeripilot/data/NightscoutEntryParser.kt");
  const mapper = read("android/app/src/main/java/app/sokeripilot/data/XDripReadingMapper.kt");
  assert(parser.includes("MIN_MGDL..GlucoseReading.MAX_MGDL"), "Nightscout range check missing");
  assert(mapper.includes("MIN_MGDL..GlucoseReading.MAX_MGDL"), "xDrip range check missing");
  assert(mapper.includes("FUTURE_TOLERANCE_MILLIS"), "time check missing");
  return "range + time";
});

/* ------------------------------------------------------------------ */
/* Dependencies                                                        */
/* ------------------------------------------------------------------ */

check("dependencies: pinned", () => {
  const gradle = read("android/app/build.gradle.kts");
  assert(!/\+\s*"/.test(gradle), "dynamic version (+) present");
  assert(!gradle.includes("SNAPSHOT"), "SNAPSHOT dependency present");
  assert(!/latest\.(release|integration)/.test(gradle), "latest version present");
  assert(gradle.includes("androidx.car.app:app:1.7.0"), "Car App 1.7.0 is not pinned");
  return "all versions pinned";
});

check("build: R8 and resource shrinking enabled", () => {
  const gradle = read("android/app/build.gradle.kts");
  assert(gradle.includes("isMinifyEnabled = true"), "minify is off");
  assert(gradle.includes("isShrinkResources = true"), "resource shrinking is off");
  return "release minify + shrink";
});

/* ------------------------------------------------------------------ */
/* Tests                                                               */
/* ------------------------------------------------------------------ */

check("tests: unit tests present", () => {
  const tests = walk("android/app/src/test").filter((file) => file.endsWith(".kt"));
  assert(tests.length >= 8, `test file count ${tests.length}`);
  const policy = read("android/app/src/test/java/app/sokeripilot/notify/AlertPolicyTest.kt");
  assert(policy.includes("mute window") && policy.includes("throttled"), "alert policy tests missing");
  return `${tests.length} test files (including alert policy)`;
});

/* ------------------------------------------------------------------ */
/* Landing page                                                        */
/* ------------------------------------------------------------------ */

const html = read("landing/index.html");
const js = read("landing/app.js");

check("site: one strict CSP for every response", () => {
  const headers = read("landing/_headers");
  const policy = /Content-Security-Policy:\s*(.+)/.exec(headers)?.[1] ?? "";
  const directives = [
    "default-src 'self'",
    "img-src 'self' data:",
    "style-src 'self'",
    "script-src 'self'",
    "connect-src 'none'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'"
  ];
  for (const directive of directives) assert(policy.includes(directive), `CSP is missing ${directive}`);
  assert(!/'unsafe-(inline|eval)'/.test(policy), "the CSP allows inline or eval");
  for (const page of [
    "landing/index.html",
    "landing/download/index.html",
    "landing/install/index.html",
    "landing/privacy/index.html",
    "landing/404.html"
  ]) {
    assert(!read(page).includes('http-equiv="Content-Security-Policy"'), `${page} keeps a second CSP copy`);
  }
  assert(headers.includes("Strict-Transport-Security:"), "HSTS missing");
  assert(headers.includes("X-Content-Type-Options: nosniff"), "nosniff missing for documents");
  assert(headers.includes("X-Frame-Options: DENY"), "X-Frame-Options missing");
  assert(headers.includes("Referrer-Policy: no-referrer"), "Referrer-Policy missing");
  assert(headers.includes("Permissions-Policy:"), "Permissions-Policy missing");
  return "header CSP with frame-ancestors, HSTS, nosniff, DENY, no-referrer; no meta copies";
});

check("landing: no inline script/style", () => {
  // A JSON-LD block is data, never executed; every executable script must load from a file.
  assert(
    !/<script(?![^>]*\bsrc=)(?![^>]*type="application\/ld\+json")/i.test(html),
    "script without src present"
  );
  assert(!/\sstyle="/i.test(html), "inline style present");
  assert(!/<style/i.test(html), "inline style block present");
  assert(!/\son[a-z]+\s*=/i.test(html), "inline event handler present");
  return "all assets external";
});

check("landing: no external subresources", () => {
  for (const file of landingFiles) {
    const content = read(file);
    assert(!/(src|poster|action)="https?:/i.test(content), `external subresource in ${file}`);
    assert(
      !/<link[^>]+rel="(?:stylesheet|icon|preload|prefetch|manifest)"[^>]*href="https?:/i.test(content),
      `external <link> in ${file}`
    );
    assert(!/url\(\s*['"]?https?:/i.test(content), `external URL in ${file}`);
  }
  // An outbound <a> loads nothing, so it may point off-site — over HTTPS only.
  const anchors = [...html.matchAll(/<a\b[^>]*href="([^"]+)"/g)].map((match) => match[1]);
  const remote = anchors.filter((href) => /^https?:/i.test(href));
  for (const href of remote) assert(href.startsWith("https://"), `insecure outbound link: ${href}`);
  assert(!/\b(fetch|XMLHttpRequest|WebSocket|sendBeacon)\b/.test(js), "network call present");
  return `zero third-party requests; ${remote.length} outbound links, all https`;
});

check("landing: no dangerous DOM APIs", () => {
  assert(!/\beval\s*\(/.test(js), "eval is used");
  assert(!/new\s+Function/.test(js), "new Function is used");
  assert(!/innerHTML|outerHTML|document\.write/.test(js), "HTML injection API is used");
  assert(!/localStorage|sessionStorage/.test(js), "browser storage is used");
  return "no eval/innerHTML/storage";
});

check("landing: local asset references exist", () => {
  const refs = [...html.matchAll(/(?:src|href)="([^"#][^"]*)"/g)].map((m) =>
    m[1].split(/[?#]/)[0]
  );
  const local = refs.filter(
    (ref) => !ref.startsWith("#") && !ref.startsWith("mailto:") && !/^https?:/i.test(ref)
  );
  for (const ref of local) {
    assert(exists("landing/" + ref), `missing file: ${ref}`);
  }
  assert(html.includes("assets/favicon.svg"), "favicon reference missing");
  return `${local.length} local references verified`;
});

check("landing: the static pages carry no executable code", () => {
  for (const page of [
    "landing/install/index.html",
    "landing/privacy/index.html",
    "landing/404.html",
    "landing/download/index.html"
  ]) {
    assert(!/<script/i.test(read(page)), `${page} contains a script`);
    assert(!/\sstyle="/i.test(read(page)), `${page} has an inline style`);
  }
  return "install, privacy, 404 and archive: script-free and style-attribute-free";
});

check("landing: structured data matches the release", () => {
  const block = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(html)?.[1];
  assert(block, "JSON-LD block missing");
  const data = JSON.parse(block);
  const release = JSON.parse(read("landing/releases.json"));
  assert(data["@type"] === "MobileApplication", `JSON-LD type is ${data["@type"]}`);
  assert(data.softwareVersion === release.current, `JSON-LD version is ${data.softwareVersion}`);
  assert(
    data.downloadUrl.endsWith(`/download/SokeriPilot-${release.current}.apk`),
    `JSON-LD downloadUrl is ${data.downloadUrl}`
  );
  return `MobileApplication ${data.softwareVersion}`;
});

check("site: crawler files agree with the published origin", () => {
  const origin = JSON.parse(read("deploy/site.json")).urls.production.replace(/\/+$/, "");
  const robots = read("landing/robots.txt");
  const sitemap = read("landing/sitemap.xml");
  assert(robots.includes(`Sitemap: ${origin}/sitemap.xml`), "robots.txt points at another sitemap");
  for (const path of ["/", "/install/", "/privacy/", "/download/"]) {
    assert(sitemap.includes(`<loc>${origin}${path}</loc>`), `sitemap is missing ${path}`);
  }
  return `robots + sitemap on ${origin}`;
});

check("site: a missing path answers with the 404 page", () => {
  assert(exists("landing/404.html"), "landing/404.html is missing");
  const worker = read("deploy/worker.js");
  assert(worker.includes("/404.html"), "the worker does not serve the 404 page");
  assert(worker.includes("status: 404"), "the 404 fallback loses the status code");
  assert(read("landing/404.html").includes('href="/#download"'), "the 404 page has no way back to the download");
  return "branded 404 with a download link";
});

check("landing: accessibility basics", () => {
  assert(html.includes('lang="en"'), "lang=en missing");
  assert(html.includes('class="skip"'), "skip link missing");
  assert(html.includes("aria-label"), "aria-label missing");
  assert(read("landing/styles.css").includes("prefers-reduced-motion"), "reduced motion missing in CSS");
  assert(read("landing/app.js").includes("prefers-reduced-motion"), "reduced motion missing in JS");
  assert(!/<img(?![^>]*\balt=)/i.test(html), "img without alt present");
  return "language, skip link, reduced motion";
});

/* ------------------------------------------------------------------ */

console.log("");
if (failures.length > 0) {
  console.log(`RESULT: ${passed} passed, ${failures.length} failed.`);
  console.log("Failed checks: " + failures.join(", "));
  process.exit(1);
}

console.log(`RESULT: all ${passed} checks passed.`);
