#!/usr/bin/env node
/**
 * HTTP smoke test against the running landing server.
 *
 *   python -m http.server 4173 --bind 127.0.0.1 --directory landing
 *   node tools/smoke.mjs
 *
 * Every release-specific target is read from landing/releases.json, so a new
 * version is covered without editing this file.
 */
import { readFileSync } from "node:fs";

const base = process.env.BASE_URL ?? "http://127.0.0.1:4173";
const release = JSON.parse(readFileSync("landing/releases.json", "utf8"));
const current = release.releases.find((item) => item.version === release.current);
const stamp = release.assetStamp;

const targets = [
  { path: "/", type: "text/html", mustInclude: "SokeriPilot" },
  { path: `/styles.css?v=${stamp}`, type: "text/css", mustInclude: "--mint" },
  { path: `/showcase.css?v=${stamp}`, type: "text/css", mustInclude: "float-card" },
  { path: `/signals.css?v=${stamp}`, type: "text/css", mustInclude: "dash__panel" },
  { path: "/app.js", type: "javascript", mustInclude: "prefers-reduced-motion" },
  { path: "/assets/favicon.svg", type: "image/svg+xml", mustInclude: "<svg" },
  { path: "/assets/install-qr.svg", type: "image/svg+xml", mustInclude: "<svg" },
  {
    path: "/releases.json",
    types: ["application/json", "text/plain"],
    mustInclude: `"current": "${release.current}"`
  },
  { path: "/download/", type: "text/html", mustInclude: "Every signed release" },
  { path: "/install/", type: "text/html", mustInclude: "From download to the cockpit" },
  { path: "/privacy/", type: "text/html", mustInclude: "Your readings stay with you" },
  { path: "/404.html", type: "text/html", mustInclude: "This page took a wrong turn" },
  { path: "/robots.txt", type: "text/plain", mustInclude: "Sitemap:" },
  { path: "/sitemap.xml", types: ["application/xml", "text/xml"], mustInclude: "<urlset" },
  ...release.releases.map((item) => ({
    path: `/download/${item.file}`,
    types: ["application/vnd.android.package-archive", "application/octet-stream"],
    method: "HEAD",
    minBytes: 500000
  }))
];

let failures = 0;

for (const target of targets) {
  const url = base + target.path;
  try {
    const response = await fetch(url, { redirect: "error", method: target.method ?? "GET" });
    const body = target.method === "HEAD" ? "" : await response.text();
    const type = response.headers.get("content-type") ?? "";
    const length = Number(response.headers.get("content-length") ?? 0);
    const problems = [];

    if (response.status !== 200) problems.push(`HTTP ${response.status}`);
    const types = target.types ?? [target.type];
    if (!types.some((candidate) => type.includes(candidate))) problems.push(`content-type ${type}`);
    if (target.mustInclude && !body.includes(target.mustInclude)) problems.push(`missing content: ${target.mustInclude}`);
    if (target.minBytes && length < target.minBytes) problems.push(`content-length ${length}`);

    if (problems.length > 0) {
      failures += 1;
      console.log(`FAIL  ${target.path} — ${problems.join(", ")}`);
    } else {
      console.log(`PASS  ${target.path} — ${response.status} ${type.split(";")[0]} · ${body.length || length} bytes`);
    }
  } catch (error) {
    failures += 1;
    console.log(`FAIL  ${target.path} — ${error.message}`);
  }
}

if (failures > 0) {
  console.log(`\nRESULT: ${failures} requests failed.`);
  process.exit(1);
}

console.log(`\nRESULT: all ${targets.length} requests passed (${base}).`);
