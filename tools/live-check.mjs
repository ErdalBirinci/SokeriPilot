#!/usr/bin/env node
/**
 * Live-site verification for the published GlucoPilot landing.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/live-check.mjs
 *   BASE_URL=https://glucopilot-civgdk-preview.freebuff.site node tools/live-check.mjs
 *
 * Everything release-specific comes from landing/releases.json, so the same
 * command verifies the preview and production. Verifies, against the deployed
 * site:
 *   - the page is served and pins the current version, hash, QR code and archive
 *   - each stylesheet at the current ?v= stamp is fresh (200, right type, no
 *     stale caching), which is the failure a cached document used to hide
 *   - the hero image is a decodable JPEG of the expected size
 *   - the current APK arrives with the package MIME type, the versioned
 *     filename, the exact release size and a SHA-256 that matches both the
 *     local file and the hash printed on the page
 *   - the archive page lists every release, and every archived APK is served
 *   - releases.json is served and agrees with the page
 *   - the served QR code still decodes to the production origin
 */
import { readFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { decodeQrSvg } from "./qr.mjs";

const ROOT = process.cwd();
const BASE = (process.env.BASE_URL ?? "https://glucopilot-civgdk.freebuff.site").replace(/\/+$/, "");
const HERO_PATH = "/assets/android-auto.jpg";
const APK_MIME = "application/vnd.android.package-archive";

const manifest = JSON.parse(readFileSync(join(ROOT, "landing/releases.json"), "utf8"));
const current = manifest.releases.find((release) => release.version === manifest.current);
const stamp = manifest.assetStamp;
// The QR always points at the published site, even when this runs against the preview.
const origin = JSON.parse(readFileSync(join(ROOT, "deploy/site.json"), "utf8"))
  .urls.production.replace(/\/+$/, "");

const local = {
  hero: join(ROOT, "landing/assets/android-auto.jpg"),
  apk: join(ROOT, "landing/download", current.file)
};

const stylesheets = [
  { path: `/styles.css?v=${stamp}`, marker: "--mint" },
  { path: `/showcase.css?v=${stamp}`, marker: "float-card" },
  { path: `/signals.css?v=${stamp}`, marker: "dash__panel" },
  { path: "/app.js", marker: "prefers-reduced-motion" }
];

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

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function kb(bytes) {
  return `${(bytes / 1024).toFixed(1)} KB`;
}

async function request(path, method = "GET") {
  const response = await fetch(BASE + path, {
    method,
    redirect: "follow",
    headers: { "cache-control": "no-cache" }
  });
  const body = method === "HEAD" ? Buffer.alloc(0) : Buffer.from(await response.arrayBuffer());
  return {
    response,
    body,
    type: (response.headers.get("content-type") ?? "").split(";")[0].trim()
  };
}

console.log(`Live check: ${BASE} · ${manifest.current} (?v=${stamp})\n`);

/* ------------------------------------------------------------------ */
/* 1. Landing page                                                     */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/");
  const html = body.toString("utf8");
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "text/html") throw new Error(`content-type ${type || "(none)"}`);
  if (!html.includes('src="assets/android-auto.jpg"')) throw new Error("hero image reference missing");
  if (!html.includes(`href="download/${current.file}"`)) throw new Error("APK link missing");
  if (!html.includes('class="shot__img"')) throw new Error("hero figure markup missing");
  if (!html.includes('src="assets/install-qr.svg"')) throw new Error("install QR code missing");
  if (!html.includes('href="download/"')) throw new Error("archive link missing");
  if (!html.includes(current.sha256)) throw new Error("displayed SHA-256 does not match releases.json");

  const stamps = [...html.matchAll(/\?v=([^"]+)"/g)].map((match) => match[1]);
  if (stamps.length !== 3) throw new Error(`${stamps.length} stamped stylesheets, expected 3`);
  const stale = stamps.filter((value) => value !== stamp);
  if (stale.length > 0) throw new Error(`stale stylesheet stamp ?v=${stale[0]}`);

  pass("live: landing page served", `200 text/html · ${body.length} bytes · ${manifest.current}`);
} catch (error) {
  fail("live: landing page served", error.message);
}

/* ------------------------------------------------------------------ */
/* 2. Stylesheets and script                                           */
/* ------------------------------------------------------------------ */

try {
  const details = [];
  for (const asset of stylesheets) {
    const { response, body, type } = await request(asset.path);
    if (response.status !== 200) throw new Error(`${asset.path} HTTP ${response.status}`);
    if (type !== "text/css" && type !== "javascript" && type !== "text/javascript") {
      throw new Error(`${asset.path} content-type ${type || "(none)"}`);
    }
    const text = body.toString("utf8");
    if (!text.includes(asset.marker)) throw new Error(`${asset.path} is not the expected file`);
    const cache = response.headers.get("cache-control") ?? "";
    if (!cache.includes("must-revalidate")) throw new Error(`${asset.path} cache-control "${cache}"`);
    details.push(`${asset.path.split("?")[0]} ${kb(body.length)}`);
  }
  pass("live: stylesheets and script are current", details.join(" · "));
} catch (error) {
  fail("live: stylesheets and script are current", error.message);
}

/* ------------------------------------------------------------------ */
/* 3. Hero image                                                       */
/* ------------------------------------------------------------------ */

try {
  const expected = statSync(local.hero).size;
  const { response, body, type } = await request(HERO_PATH);
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "image/jpeg") throw new Error(`content-type ${type || "(none)"}`);
  if (body.length !== expected) throw new Error(`${body.length} bytes, expected ${expected}`);
  if (!(body[0] === 0xff && body[1] === 0xd8 && body[2] === 0xff)) throw new Error("body is not a JPEG");

  const head = await request(HERO_PATH, "HEAD");
  const advertised = Number(head.response.headers.get("content-length") ?? 0);
  if (advertised && advertised !== expected) throw new Error(`content-length ${advertised}, expected ${expected}`);

  pass("live: hero image loads", `200 image/jpeg · ${kb(expected)} · JPEG header ok`);
} catch (error) {
  fail("live: hero image loads", error.message);
}

/* ------------------------------------------------------------------ */
/* 4. Current APK                                                      */
/* ------------------------------------------------------------------ */

try {
  const expected = statSync(local.apk).size;
  const { response, body, type } = await request(`/download/${current.file}`);
  const disposition = response.headers.get("content-disposition") ?? "";

  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== APK_MIME) throw new Error(`content-type ${type || "(none)"}, expected ${APK_MIME}`);
  if (body.length !== expected) throw new Error(`downloaded ${body.length} bytes, expected ${expected}`);
  if (!body.subarray(0, 2).equals(Buffer.from([0x50, 0x4b]))) throw new Error("body is not a ZIP/APK");
  if (!/^attachment/i.test(disposition)) throw new Error(`content-disposition ${disposition || "(none)"}`);
  if (!disposition.includes(`filename="${current.file}"`)) {
    throw new Error(`content-disposition renames the file: ${disposition}`);
  }

  const liveHash = sha256(body);
  if (liveHash !== current.sha256) throw new Error(`sha256 ${liveHash.slice(0, 12)}… does not match releases.json`);
  if (liveHash !== sha256(readFileSync(local.apk))) throw new Error("live bytes differ from the local APK");

  pass("live: current APK download", `200 ${APK_MIME} · ${kb(expected)} · sha256 ${liveHash.slice(0, 12)}…`);
} catch (error) {
  fail("live: current APK download", error.message);
}

/* ------------------------------------------------------------------ */
/* 5. Archive                                                          */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/download/");
  const html = body.toString("utf8");
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "text/html") throw new Error(`content-type ${type || "(none)"}`);
  for (const release of manifest.releases) {
    if (!html.includes(`href="${release.file}"`)) throw new Error(`${release.file} is not listed`);
    if (!html.includes(release.sha256)) throw new Error(`${release.file} hash is not listed`);
  }
  for (const release of manifest.releases) {
    const head = await request(`/download/${release.file}`, "HEAD");
    if (head.response.status !== 200) throw new Error(`${release.file} HTTP ${head.response.status}`);
    if (head.type !== APK_MIME) throw new Error(`${release.file} content-type ${head.type || "(none)"}`);
  }
  pass("live: release archive", `200 text/html · ${manifest.releases.length} APKs listed and served`);
} catch (error) {
  fail("live: release archive", error.message);
}

/* ------------------------------------------------------------------ */
/* 6. Release manifest                                                 */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/releases.json");
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "application/json") throw new Error(`content-type ${type || "(none)"}`);
  const served = JSON.parse(body.toString("utf8"));
  if (served.current !== manifest.current) throw new Error(`served current ${served.current}`);
  const entry = served.releases?.find((release) => release.version === served.current);
  if (!entry || entry.sha256 !== current.sha256) throw new Error("served APK hash differs from the local manifest");
  pass("live: release manifest", `200 application/json · current ${served.current}`);
} catch (error) {
  fail("live: release manifest", error.message);
}

/* ------------------------------------------------------------------ */
/* 7. Install QR code                                                  */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/assets/install-qr.svg");
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "image/svg+xml") throw new Error(`content-type ${type || "(none)"}`);
  const qr = decodeQrSvg(body.toString("utf8"));
  if (!qr.structureOk || !qr.syndromesOk) throw new Error("the served symbol is damaged");
  if (qr.text !== `${origin}/#download`) throw new Error(`the served QR resolves to "${qr.text}"`);
  pass("live: install QR code", `200 image/svg+xml · ${kb(body.length)} · ${qr.text}`);
} catch (error) {
  fail("live: install QR code", error.message);
}

/* ------------------------------------------------------------------ */
/* 8. Security headers                                                 */
/* ------------------------------------------------------------------ */

try {
  const { response } = await request("/");
  const header = (name) => response.headers.get(name) ?? "";
  const csp = header("content-security-policy");
  const missing = [];
  if (!csp.includes("default-src 'self'")) missing.push("CSP default-src");
  if (!csp.includes("frame-ancestors 'none'")) missing.push("CSP frame-ancestors");
  if (!header("strict-transport-security").includes("max-age=")) missing.push("HSTS");
  if (header("x-content-type-options").toLowerCase() !== "nosniff") missing.push("nosniff");
  if (header("x-frame-options").toUpperCase() !== "DENY") missing.push("X-Frame-Options");
  if (header("referrer-policy") !== "no-referrer") missing.push("Referrer-Policy");
  if (missing.length > 0) throw new Error(`missing or weak: ${missing.join(", ")}`);
  pass("live: security headers", "CSP with frame-ancestors · HSTS · nosniff · DENY · no-referrer");
} catch (error) {
  fail("live: security headers", error.message);
}

/* ------------------------------------------------------------------ */
/* 9. Guide pages and crawler files                                    */
/* ------------------------------------------------------------------ */

try {
  const pages = [
    { path: "/install/", marker: "From download to the cockpit" },
    { path: "/privacy/", marker: "Your readings stay with you" }
  ];
  const details = [];
  for (const page of pages) {
    const { response, body, type } = await request(page.path);
    if (response.status !== 200) throw new Error(`${page.path} HTTP ${response.status}`);
    if (type !== "text/html") throw new Error(`${page.path} content-type ${type || "(none)"}`);
    if (!body.toString("utf8").includes(page.marker)) throw new Error(`${page.path} is not the expected page`);
    details.push(`${page.path} ${kb(body.length)}`);
  }
  const robots = await request("/robots.txt");
  if (robots.response.status !== 200) throw new Error(`robots.txt HTTP ${robots.response.status}`);
  if (!robots.body.toString("utf8").includes(`${origin}/sitemap.xml`)) throw new Error("robots.txt points elsewhere");
  const sitemap = await request("/sitemap.xml");
  if (sitemap.response.status !== 200) throw new Error(`sitemap.xml HTTP ${sitemap.response.status}`);
  for (const path of ["/install/", "/privacy/"]) {
    if (!sitemap.body.toString("utf8").includes(`${origin}${path}`)) throw new Error(`sitemap is missing ${path}`);
  }
  pass("live: guide pages and crawler files", `${details.join(" · ")} · robots + sitemap`);
} catch (error) {
  fail("live: guide pages and crawler files", error.message);
}

/* ------------------------------------------------------------------ */
/* 10. Unknown path                                                    */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/this-path-does-not-exist");
  if (response.status !== 404) throw new Error(`HTTP ${response.status}, expected 404`);
  if (type !== "text/html") throw new Error(`content-type ${type || "(none)"}`);
  if (!body.toString("utf8").includes("This page took a wrong turn")) throw new Error("the branded 404 page is missing");
  pass("live: unknown path", "404 text/html · branded page with a download link");
} catch (error) {
  fail("live: unknown path", error.message);
}

/* ------------------------------------------------------------------ */

console.log("");
if (failures.length > 0) {
  console.log(`RESULT: ${passed} passed, ${failures.length} failed.`);
  console.log("Failed checks: " + failures.join(", "));
  process.exit(1);
}

console.log(`RESULT: all ${passed} live checks passed (${BASE}).`);
