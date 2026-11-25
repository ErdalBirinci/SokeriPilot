#!/usr/bin/env node
/**
 * HTTP smoke test against the running landing server.
 *
 *   python -m http.server 4173 --bind 127.0.0.1 --directory landing
 *   node tools/smoke.mjs
 */
const base = process.env.BASE_URL ?? "http://127.0.0.1:4173";

const targets = [
  { path: "/", type: "text/html", mustInclude: "GlucoPilot" },
  { path: "/styles.css", type: "text/css", mustInclude: "--mint" },
  { path: "/showcase.css", type: "text/css", mustInclude: "float-card" },
  { path: "/signals.css", type: "text/css", mustInclude: "dash__panel" },
  { path: "/app.js", type: "javascript", mustInclude: "prefers-reduced-motion" },
  { path: "/assets/favicon.svg", type: "image/svg+xml", mustInclude: "<svg" },
  {
    path: "/download/GlucoPilot-1.2.0.apk",
    types: ["application/vnd.android.package-archive", "application/octet-stream"],
    method: "HEAD",
    minBytes: 500000
  }
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
