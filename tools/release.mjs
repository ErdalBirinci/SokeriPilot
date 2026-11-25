#!/usr/bin/env node
/**
 * Bumps the landing to a new signed APK.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/release.mjs --apk landing/download/SokeriPilot-1.3.0.apk            # dry run
 *   node tools/release.mjs --apk landing/download/SokeriPilot-1.3.0.apk --write    # apply
 *
 * It reads the facts from the APK (size, SHA-256, and — when the portable SDK is
 * present — versionName and signing certificate), then rewrites the four places
 * that are easy to miss by hand: releases.json, the announcement/version/size/
 * hash/links on the landing, the per-APK _headers block, and the archive page.
 * The new asset stamp becomes <version>-1 so every page requests fresh CSS.
 *
 * Options: --version <x.y.z>, --code <n>, --date <YYYY-MM-DD>, --write,
 * --rehearsal (skip the APK identity check while trying the tool).
 *
 * Run `node tools/release-check.mjs` afterwards; it is the authority.
 */
import { readFileSync, writeFileSync, statSync, existsSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { execFileSync, execSync } from "node:child_process";
import { join, basename } from "node:path";

const ROOT = process.cwd();
const args = process.argv.slice(2);
const has = (name) => args.includes(`--${name}`);
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};

const apkPath = option("apk");
if (!apkPath || !existsSync(join(ROOT, apkPath))) {
  console.error("usage: node tools/release.mjs --apk landing/download/SokeriPilot-<version>.apk [--write]");
  process.exit(2);
}

const manifestPath = "landing/releases.json";
const index = "landing/index.html";
const archive = "landing/download/index.html";
const headers = "landing/_headers";
const guide = "landing/install/index.html";

const manifest = JSON.parse(readFileSync(join(ROOT, manifestPath), "utf8"));
const current = manifest.releases.find((release) => release.version === manifest.current);
const stamp = manifest.assetStamp;
const absoluteApk = join(ROOT, apkPath);

const version = option("version", /SokeriPilot-(\d+\.\d+\.\d+)\.apk$/.exec(basename(apkPath))?.[1]);
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error(`cannot read the version from ${apkPath}; pass --version x.y.z`);
  process.exit(2);
}

const size = statSync(absoluteApk).size;
const sha256 = createHash("sha256").update(readFileSync(absoluteApk)).digest("hex");

if (version === manifest.current) {
  if (size !== current.size || sha256 !== current.sha256) {
    console.error(
      `${version} is already current but this file differs from the manifest.\n` +
        "Bump the version (and sign a new APK) instead of replacing a published build."
    );
    process.exit(1);
  }
  console.log(`release: ${version} is already current (${size} bytes, sha256 ${sha256.slice(0, 12)}…) — nothing to do`);
  process.exit(0);
}

/* ---------- facts from the APK, when the portable toolchain exists ---------- */

function buildTools() {
  const base = join(ROOT, ".toolchain/android-sdk/build-tools");
  if (!existsSync(base)) return null;
  const versions = readdirSync(base).sort((a, b) => {
    const left = a.split(".").map(Number);
    const right = b.split(".").map(Number);
    for (let i = 0; i < 3; i += 1) if (left[i] !== right[i]) return left[i] - right[i];
    return 0;
  });
  for (const entry of versions.reverse()) {
    const dir = join(base, entry);
    const find = (names) => names.map((name) => join(dir, name)).find(existsSync);
    if (find(["aapt2.exe", "aapt2"])) return { aapt2: find(["aapt2.exe", "aapt2"]), apksigner: find(["apksigner.bat", "apksigner"]) };
  }
  return null;
}

function javaHome() {
  const binary = process.platform === "win32" ? "bin/java.exe" : "bin/java";
  return [join(ROOT, ".toolchain/jdk"), process.env.JAVA_HOME]
    .filter(Boolean)
    .find((home) => existsSync(join(home, binary)));
}

const tools = buildTools();
const jdk = javaHome();
let certSha256 = current.certSha256;

if (tools?.aapt2 && !has("rehearsal")) {
  const badging = execFileSync(tools.aapt2, ["dump", "badging", absoluteApk], { encoding: "utf8" });
  const versionName = /versionName='([^']+)'/.exec(badging)?.[1];
  if (versionName !== version) {
    console.error(`${basename(apkPath)} reports versionName ${versionName}, not ${version}. A release APK must be rebuilt and signed.`);
    process.exit(1);
  }
}

if (tools?.apksigner && jdk) {
  const output = execSync(`"${tools.apksigner}" verify --print-certs "${absoluteApk}"`, {
    encoding: "utf8",
    env: { ...process.env, JAVA_HOME: jdk },
    stdio: ["ignore", "pipe", "pipe"]
  });
  const found = /certificate SHA-256 digest:\s*([0-9a-f]{64})/i.exec(output)?.[1];
  if (found) certSha256 = found;
  if (certSha256 !== current.certSha256) {
    console.error(
      `the APK is signed by ${certSha256.slice(0, 12)}…, the published releases use ${current.certSha256.slice(0, 12)}….\n` +
        "Android cannot install this over the installed app. Restore the release key instead of shipping it."
    );
    process.exit(1);
  }
}

/* ---------- derived content ---------- */

const date = option("date", new Date().toISOString().slice(0, 10));
if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
  console.error(`--date "${date}" is not YYYY-MM-DD`);
  process.exit(2);
}
const versionCode = Number(option("code", current.versionCode + 1));
const dateText = new Date(`${date}T00:00:00Z`).toLocaleDateString("en-GB", {
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: "UTC"
});
const file = `SokeriPilot-${version}.apk`;
const megabytes = (size / 1024 / 1024).toFixed(1);
const oldMegabytes = (current.size / 1024 / 1024).toFixed(1);
const nextStamp = `${version}-1`;

const nextManifest = {
  current: version,
  assetStamp: nextStamp,
  releases: [
    { version, versionCode, date, file, size, sha256, certSha256 },
    ...manifest.releases
  ]
};

let nextIndex = readFileSync(join(ROOT, index), "utf8");
nextIndex = nextIndex.split(`download/SokeriPilot-${current.version}.apk`).join(`download/${file}`);
nextIndex = nextIndex.replace(`<span class="announce__tag">${current.version}</span>`, `<span class="announce__tag">${version}</span>`);
nextIndex = nextIndex.replace(
  `<li><span>Version</span><strong>${current.version}</strong></li>`,
  `<li><span>Version</span><strong>${version}</strong></li>`
);
nextIndex = nextIndex.replace(
  `<li><span>Size</span><strong>${oldMegabytes} MB</strong></li>`,
  `<li><span>Size</span><strong>${megabytes} MB</strong></li>`
);
nextIndex = nextIndex.split(current.sha256).join(sha256);
nextIndex = nextIndex.split(current.certSha256).join(certSha256);
nextIndex = nextIndex.split(`?v=${stamp}`).join(`?v=${nextStamp}`);
nextIndex = nextIndex.replace(`"softwareVersion": "${current.version}"`, `"softwareVersion": "${version}"`);

const archiveEntry = `      <li class="release">
        <span class="release__ver">${version}</span>
        <div>
          <h3>
            <a href="${file}" download>${file}</a>
            <span class="archive__flag">Latest</span>
          </h3>
          <p>${size.toLocaleString("en-US")} bytes · ${dateText} · versionCode ${versionCode}</p>
          <p class="archive__hash">
            <span>SHA-256</span>
            <code>${sha256}</code>
          </p>
        </div>
      </li>

`;

let nextArchive = readFileSync(join(ROOT, archive), "utf8");
nextArchive = nextArchive.replace('            <span class="archive__flag">Latest</span>\n', "");
nextArchive = nextArchive.replace('      <li class="release">', archiveEntry + "      <li class=\"release\">");
nextArchive = nextArchive.split(`?v=${stamp}`).join(`?v=${nextStamp}`);

const headerBlock = `/download/${file}
  Content-Type: application/vnd.android.package-archive
  Content-Disposition: attachment; filename="${file}"
  X-Content-Type-Options: nosniff
  Cache-Control: public, max-age=3600

`;

let nextHeaders = readFileSync(join(ROOT, headers), "utf8");
nextHeaders = nextHeaders.replace(`/download/${current.file}\n`, headerBlock + `/download/${current.file}\n`);

// The install guide names the file, its size and the start of its hash; keep it current.
let nextGuide = readFileSync(join(ROOT, guide), "utf8");
nextGuide = nextGuide.split(`SokeriPilot-${current.version}.apk`).join(file);
nextGuide = nextGuide.replace(`${oldMegabytes} MB`, `${megabytes} MB`);
nextGuide = nextGuide.replace(`<code>${current.sha256.slice(0, 8)}</code>`, `<code>${sha256.slice(0, 8)}</code>`);

// Every page links its stylesheet with the stamp; a release moves all of them at once.
const restamped = (content) => content.split(`?v=${stamp}`).join(`?v=${nextStamp}`);
nextGuide = restamped(nextGuide);
nextArchive = restamped(nextArchive);

/* ---------- report or apply ---------- */

const planned = [
  { file: manifestPath, before: readFileSync(join(ROOT, manifestPath), "utf8"), after: JSON.stringify(nextManifest, null, 2) + "\n" },
  { file: index, before: readFileSync(join(ROOT, index), "utf8"), after: nextIndex },
  { file: archive, before: readFileSync(join(ROOT, archive), "utf8"), after: nextArchive },
  { file: headers, before: readFileSync(join(ROOT, headers), "utf8"), after: nextHeaders },
  { file: guide, before: readFileSync(join(ROOT, guide), "utf8"), after: nextGuide },
  { file: "landing/privacy/index.html", before: readFileSync(join(ROOT, "landing/privacy/index.html"), "utf8"), after: restamped(readFileSync(join(ROOT, "landing/privacy/index.html"), "utf8")) },
  { file: "landing/404.html", before: readFileSync(join(ROOT, "landing/404.html"), "utf8"), after: restamped(readFileSync(join(ROOT, "landing/404.html"), "utf8")) }
];

const changed = planned.filter((entry) => entry.before !== entry.after);
for (const entry of changed) {
  const before = entry.before.split("\n").length;
  const after = entry.after.split("\n").length;
  console.log(`plan  ${entry.file} — ${before} → ${after} lines`);
}

if (!has("write")) {
  console.log(
    `\nrelease: ${version} ready to apply — ${size} bytes, sha256 ${sha256.slice(0, 12)}…, ` +
      `versionCode ${versionCode}, stamp ${nextStamp}`
  );
  console.log("dry run only; re-run with --write, then node tools/release-check.mjs");
  process.exit(0);
}

for (const entry of changed) writeFileSync(join(ROOT, entry.file), entry.after);
console.log(
  `\nrelease: ${version} written to ${changed.length} files — ${size} bytes, sha256 ${sha256.slice(0, 12)}…, ` +
    `versionCode ${versionCode}, stamp ${nextStamp}`
);
console.log("Next: update the announcement and release-notes copy if they describe 1.2 features,");
console.log("then run release-check, security-check and smoke; deploy preview before production.");
