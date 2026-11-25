#!/usr/bin/env node
/**
 * Live-site verification for the published GlucoPilot landing.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/live-check.mjs
 *   BASE_URL=https://glucopilot-civgdk.freebuff.site node tools/live-check.mjs
 *
 * Verifies, against the deployed site:
 *   - the landing page is served and references the hero image and the APK
 *   - the hero image is a decodable JPEG of the expected size
 *   - the APK arrives with the Android package MIME type, download
 *     disposition, the exact release size and a matching SHA-256
 */
import { readFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";

const ROOT = process.cwd();
const BASE = (process.env.BASE_URL ?? "https://glucopilot-civgdk.freebuff.site").replace(/\/+$/, "");
const HERO_PATH = "/assets/android-auto.jpg";
const APK_PATH = "/download/GlucoPilot-1.1.3.apk";
const APK_MIME = "application/vnd.android.package-archive";

const local = {
  hero: join(ROOT, "landing/assets/android-auto.jpg"),
  apk: join(ROOT, "landing/download/GlucoPilot-1.1.3.apk")
};

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

console.log(`Live check: ${BASE}\n`);

/* ------------------------------------------------------------------ */
/* 1. Landing page                                                     */
/* ------------------------------------------------------------------ */

try {
  const { response, body, type } = await request("/");
  const html = body.toString("utf8");
  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== "text/html") throw new Error(`content-type ${type || "(none)"}`);
  if (!html.includes('src="assets/android-auto.jpg"')) throw new Error("hero image reference missing");
  if (!html.includes('href="download/GlucoPilot-1.1.3.apk"')) throw new Error("APK link missing");
  if (!html.includes('class="shot__img"')) throw new Error("hero figure markup missing");
  pass("live: landing page served", `200 text/html · ${body.length} bytes`);
} catch (error) {
  fail("live: landing page served", error.message);
}

/* ------------------------------------------------------------------ */
/* 2. Hero image                                                       */
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
/* 3. APK download                                                     */
/* ------------------------------------------------------------------ */

try {
  const expected = statSync(local.apk).size;
  const { response, body, type } = await request(APK_PATH);
  const disposition = response.headers.get("content-disposition") ?? "";

  if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
  if (type !== APK_MIME) throw new Error(`content-type ${type || "(none)"}, expected ${APK_MIME}`);
  if (body.length !== expected) throw new Error(`downloaded ${body.length} bytes, expected ${expected}`);
  if (!body.subarray(0, 2).equals(Buffer.from([0x50, 0x4b]))) throw new Error("body is not a ZIP/APK");
  if (!/^attachment/i.test(disposition)) throw new Error(`content-disposition ${disposition || "(none)"}`);

  const liveHash = sha256(body);
  const localHash = sha256(readFileSync(local.apk));
  if (liveHash !== localHash) throw new Error(`sha256 ${liveHash.slice(0, 12)}… != ${localHash.slice(0, 12)}…`);

  pass("live: APK download", `200 ${APK_MIME} · ${kb(expected)} · sha256 ${liveHash.slice(0, 12)}…`);
} catch (error) {
  fail("live: APK download", error.message);
}

/* ------------------------------------------------------------------ */

console.log("");
if (failures.length > 0) {
  console.log(`RESULT: ${passed} passed, ${failures.length} failed.`);
  console.log("Failed checks: " + failures.join(", "));
  process.exit(1);
}

console.log(`RESULT: all ${passed} live checks passed (${BASE}).`);
