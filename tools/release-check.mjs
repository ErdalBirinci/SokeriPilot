#!/usr/bin/env node
/**
 * Release consistency check for the published SokeriPilot landing.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/release-check.mjs
 *
 * `landing/releases.json` is the single description of a release. This command
 * proves that everything else agrees with it: the APK bytes (size and SHA-256),
 * the version and hash printed on the page, the per-file download headers, the
 * archive page, the asset stamp, and — when the portable Android SDK is present
 * — the versionName and signing certificate inside the APK itself.
 */
import { readFileSync, statSync, existsSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { execFileSync, execSync } from "node:child_process";
import { join } from "node:path";
import { decodeQrSvg } from "./qr.mjs";

const ROOT = process.cwd();
const failures = [];
let passed = 0;
let skipped = 0;

function pass(name, detail = "") {
  passed += 1;
  console.log(`PASS  ${name}${detail ? " — " + detail : ""}`);
}

function fail(name, reason) {
  failures.push(name);
  console.log(`FAIL  ${name} — ${reason}`);
}

function skip(name, reason) {
  skipped += 1;
  console.log(`SKIP  ${name} — ${reason}`);
}

function check(name, fn) {
  try {
    pass(name, fn() || "");
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

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function byVersion(a, b) {
  const left = a.split(".").map(Number);
  const right = b.split(".").map(Number);
  for (let i = 0; i < 3; i += 1) {
    if (left[i] !== right[i]) return left[i] - right[i];
  }
  return 0;
}

const manifest = JSON.parse(read("landing/releases.json"));
const site = JSON.parse(read("deploy/site.json"));
const origin = site.urls.production.replace(/\/+$/, "");
const html = read("landing/index.html");
const archive = read("landing/download/index.html");
const headers = read("landing/_headers");
const releases = manifest.releases;
const current = releases.find((release) => release.version === manifest.current);

/* ------------------------------------------------------------------ */
/* Manifest                                                            */
/* ------------------------------------------------------------------ */

check("release: manifest is well formed", () => {
  assert(/^\d+\.\d+\.\d+$/.test(manifest.current), `current "${manifest.current}" is not semver`);
  assert(Array.isArray(releases) && releases.length > 0, "no releases listed");
  assert(releases[0].version === manifest.current, "current is not the newest entry");
  const versions = releases.map((release) => release.version);
  assert(new Set(versions).size === versions.length, "duplicate version in the manifest");
  const sorted = [...versions].sort(byVersion).reverse();
  assert(sorted.join(" ") === versions.join(" "), "releases are not ordered newest first");
  for (const release of releases) {
    assert(release.file === `SokeriPilot-${release.version}.apk`, `${release.version} file name is not SokeriPilot-<version>.apk`);
    assert(Number.isInteger(release.size) && release.size > 500_000, `${release.version} size ${release.size}`);
    assert(/^[0-9a-f]{64}$/.test(release.sha256), `${release.version} sha256 is not 64 hex characters`);
    assert(/^[0-9a-f]{64}$/.test(release.certSha256), `${release.version} certificate digest is not 64 hex characters`);
    assert(/^\d{4}-\d{2}-\d{2}$/.test(release.date), `${release.version} date is not ISO`);
    assert(Number.isInteger(release.versionCode) && release.versionCode > 0, `${release.version} versionCode`);
  }
  assert(/^\d+\.\d+\.\d+-\d+$/.test(manifest.assetStamp), `assetStamp "${manifest.assetStamp}" is not <version>-<revision>`);
  assert(manifest.assetStamp.startsWith(`${manifest.current}-`), "assetStamp does not start with the current version");
  return `${releases.length} releases, current ${manifest.current}, stamp ${manifest.assetStamp}`;
});

check("release: one signing certificate across releases", () => {
  const certificates = new Set(releases.map((release) => release.certSha256));
  assert(certificates.size === 1, `releases use ${certificates.size} different certificates`);
  return `all releases signed by ${current.certSha256.slice(0, 8)}…`;
});

/* ------------------------------------------------------------------ */
/* Files                                                               */
/* ------------------------------------------------------------------ */

check("release: APK files match the manifest", () => {
  for (const release of releases) {
    const path = join(ROOT, "landing/download", release.file);
    assert(existsSync(path), `missing ${release.file}`);
    const size = statSync(path).size;
    assert(size === release.size, `${release.file} is ${size} bytes, manifest says ${release.size}`);
    const digest = sha256(readFileSync(path));
    assert(
      digest === release.sha256,
      `${release.file} sha256 ${digest.slice(0, 12)}… does not match the manifest`
    );
  }
  return `${releases.length} APKs verified byte for byte`;
});

check("release: landing page pins the current release", () => {
  assert(html.includes(`href="download/${current.file}"`), `the page does not link ${current.file}`);
  const links = [...html.matchAll(/href="download\/SokeriPilot-([0-9.]+)\.apk"/g)].map((match) => match[1]);
  assert(links.length >= 1, "no APK links on the page");
  const stale = links.filter((version) => version !== manifest.current);
  assert(stale.length === 0, `the page still links ${stale.join(", ")}`);

  assert(html.includes(`<span class="announce__tag">${manifest.current}</span>`), "the announcement does not show the current version");
  assert(html.includes(`<li><span>Version</span><strong>${manifest.current}</strong></li>`), "the download card does not show the current version");
  assert(html.includes(current.sha256), "the displayed SHA-256 does not match the manifest");
  assert(html.includes(current.certSha256), "the signing certificate fingerprint is missing from the page");

  const megabytes = (current.size / 1024 / 1024).toFixed(1);
  assert(html.includes(`<strong>${megabytes} MB</strong>`), `the page does not say ${megabytes} MB`);
  return `${manifest.current} · ${megabytes} MB · version, size, hash and certificate shown`;
});

check("release: stylesheet stamp is consistent", () => {
  const pages = {
    "landing/index.html": html,
    "landing/download/index.html": archive,
    "landing/install/index.html": read("landing/install/index.html"),
    "landing/privacy/index.html": read("landing/privacy/index.html"),
    "landing/404.html": read("landing/404.html")
  };
  let total = 0;
  for (const [file, content] of Object.entries(pages)) {
    const stamps = [...content.matchAll(/\?v=([^"]+)"/g)].map((match) => match[1]);
    assert(stamps.length >= 1, `${file} has no stamped stylesheet`);
    const wrong = stamps.filter((stamp) => stamp !== manifest.assetStamp);
    assert(wrong.length === 0, `${file} still uses ?v=${wrong[0]}`);
    total += stamps.length;
  }
  return `${total} links across ${Object.keys(pages).length} pages at ?v=${manifest.assetStamp}`;
});

check("release: every APK has its own download headers", () => {
  const blocks = headers
    .split(/\r?\n\r?\n/)
    .map((block) => block.trim())
    .filter((block) => block && !block.startsWith("#"));
  assert(!/^\/download\/\*\.apk/m.test(headers), "a wildcard APK block would rename archived files");
  for (const release of releases) {
    const block = blocks.find((entry) => entry.split(/\r?\n/)[0] === `/download/${release.file}`);
    assert(block, `no _headers block for /download/${release.file}`);
    assert(block.includes("Content-Type: application/vnd.android.package-archive"), `${release.file} has no package MIME type`);
    assert(block.includes(`filename="${release.file}"`), `${release.file} downloads with another filename`);
    assert(block.includes("X-Content-Type-Options: nosniff"), `${release.file} is sniffable`);
  }
  assert(headers.includes("must-revalidate"), "documents and stylesheets may be cached without revalidation");
  return `${releases.length} per-file blocks + revalidating documents`;
});

check("release: archive page lists every release", () => {
  for (const release of releases) {
    assert(archive.includes(`href="${release.file}"`), `the archive does not link ${release.file}`);
    assert(archive.includes(release.sha256), `the archive does not show the SHA-256 of ${release.file}`);
    assert(
      archive.includes(`${release.size.toLocaleString("en-US")} bytes`),
      `the archive does not show the size of ${release.file}`
    );
  }
  assert(archive.includes(current.certSha256), "the archive does not show the signing certificate");
  return `${releases.length} versions with size and hash`;
});

check("release: install guide follows the current release", () => {
  const guide = read("landing/install/index.html");
  const links = [...guide.matchAll(/download\/SokeriPilot-([0-9.]+)\.apk/g)].map((match) => match[1]);
  assert(links.length >= 1, "the install guide has no APK link");
  const stale = links.filter((version) => version !== manifest.current);
  assert(stale.length === 0, `the install guide still links ${stale.join(", ")}`);
  assert(guide.includes(current.file), "the guide names another file");
  assert(guide.includes(`${(current.size / 1024 / 1024).toFixed(1)} MB`), "the guide shows another size");
  assert(guide.includes(current.sha256.slice(0, 8)), "the guide shows another hash prefix");
  return `${current.file} · size and hash prefix shown`;
});

check("release: archive page assets resolve", () => {
  const references = [...archive.matchAll(/(?:src|href)="([^"#][^"]*)"/g)].map((match) => match[1].split(/[?#]/)[0]);
  for (const reference of references) {
    if (/^https?:/.test(reference) || reference === "") continue;
    const found =
      existsSync(join(ROOT, "landing/download", reference)) || existsSync(join(ROOT, "landing", reference));
    assert(found, `the archive references a missing file: ${reference}`);
  }
  return `${references.length} local references verified`;
});

check("release: install QR code resolves to the live origin", () => {
  const qr = decodeQrSvg(read("landing/assets/install-qr.svg"));
  const expected = `${origin}/#download`;
  assert(qr.structureOk, "the symbol is missing its finder or timing patterns");
  assert(qr.formatOk, "the two format-information copies disagree");
  assert(qr.syndromesOk, "the Reed-Solomon syndromes are not zero");
  assert(qr.mode === 0b0100, `the symbol uses mode ${qr.mode}, not byte mode`);
  assert(qr.text === expected, `the QR resolves to "${qr.text}", expected ${expected}`);
  assert(html.includes('src="assets/install-qr.svg"'), "the download card does not show the QR code");
  assert(html.includes('alt="QR code'), "the QR image has no alternative text");
  return `${qr.text} · mask ${qr.mask} · decoded from the SVG`;
});

check("release: social preview points at the live site", () => {
  assert(html.includes(`<link rel="canonical" href="${origin}/">`), "canonical URL is missing");
  assert(html.includes(`property="og:url" content="${origin}/"`), "og:url is missing");
  assert(
    html.includes(`property="og:image" content="${origin}/assets/android-auto.jpg"`),
    "og:image does not point at the hero image"
  );
  assert(
    html.includes(`name="twitter:image" content="${origin}/assets/android-auto.jpg"`),
    "twitter:image is missing"
  );
  assert(html.includes('property="og:image:width" content="1392"'), "og:image:width is missing");
  assert(html.includes('name="twitter:card" content="summary_large_image"'), "twitter:card is missing");
  return origin;
});

/* ------------------------------------------------------------------ */
/* Toolchain                                                           */
/* ------------------------------------------------------------------ */

function findBuildTools() {
  const base = join(ROOT, ".toolchain/android-sdk/build-tools");
  if (!existsSync(base)) return null;
  const versions = readdirSync(base).sort(byVersion).reverse();
  for (const version of versions) {
    const dir = join(base, version);
    const find = (names) => names.map((name) => join(dir, name)).find(existsSync);
    const aapt2 = find(["aapt2.exe", "aapt2"]);
    if (!aapt2) continue;
    return { aapt2, apksigner: find(["apksigner.bat", "apksigner"]) };
  }
  return null;
}

function findJavaHome() {
  const binary = process.platform === "win32" ? "bin/java.exe" : "bin/java";
  const candidates = [join(ROOT, ".toolchain/jdk"), process.env.JAVA_HOME].filter(Boolean);
  return candidates.find((home) => existsSync(join(home, binary)));
}

const tools = findBuildTools();
const javaHome = findJavaHome();

if (tools?.aapt2) {
  check("apk: versionName matches the manifest", () => {
    for (const release of releases) {
      const output = execFileSync(tools.aapt2, ["dump", "badging", join(ROOT, "landing/download", release.file)], {
        encoding: "utf8"
      });
      const versionName = /versionName='([^']+)'/.exec(output)?.[1];
      const versionCode = /versionCode='([^']+)'/.exec(output)?.[1];
      assert(versionName === release.version, `${release.file} is versionName ${versionName}`);
      assert(
        String(release.versionCode) === versionCode,
        `${release.file} is versionCode ${versionCode}, manifest says ${release.versionCode}`
      );
    }
    return `${releases.length} APKs report the manifest version`;
  });
} else {
  skip("apk: versionName matches the manifest", "Android build-tools are not installed");
}

if (tools?.apksigner && javaHome) {
  check("apk: signed by the recorded certificate", () => {
    for (const release of releases) {
      const apk = join(ROOT, "landing/download", release.file);
      const output = execSync(`"${tools.apksigner}" verify --print-certs "${apk}"`, {
        encoding: "utf8",
        env: { ...process.env, JAVA_HOME: javaHome },
        stdio: ["ignore", "pipe", "pipe"]
      });
      const certificate = /certificate SHA-256 digest:\s*([0-9a-f]{64})/i.exec(output)?.[1];
      assert(certificate === release.certSha256, `${release.file} certificate ${certificate?.slice(0, 12)}…`);
    }
    return `certificate ${current.certSha256.slice(0, 12)}… on all releases`;
  });
} else {
  skip("apk: signed by the recorded certificate", javaHome ? "apksigner is missing" : "no JDK is available");
}

/* ------------------------------------------------------------------ */

console.log("");
if (failures.length > 0) {
  console.log(`RESULT: ${passed} passed, ${failures.length} failed${skipped ? `, ${skipped} skipped` : ""}.`);
  console.log("Failed checks: " + failures.join(", "));
  process.exit(1);
}

console.log(`RESULT: all ${passed} release checks passed${skipped ? ` (${skipped} skipped)` : ""}.`);
