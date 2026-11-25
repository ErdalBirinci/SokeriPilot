#!/usr/bin/env node
/**
 * Scheduled live-site watch.
 * No dependencies; runs on Node 18+.
 *
 *   node tools/watch.mjs
 *   BASE_URL=https://glucopilot-civgdk-preview.freebuff.site node tools/watch.mjs
 *   WATCH_WEBHOOK=https://hooks.example/… node tools/watch.mjs
 *
 * Runs `live-check.mjs`, records the result in dist/watch.json (git-ignored) and,
 * when a run fails and WATCH_WEBHOOK is set, posts a short JSON message there.
 * Exits non-zero on failure so cron, Task Scheduler or a CI schedule can act on
 * it. The webhook is the only outbound request any tool in this repository makes.
 */
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const startedAt = new Date().toISOString();
const base = process.env.BASE_URL ?? "https://glucopilot-civgdk.freebuff.site";
const run = spawnSync(process.execPath, ["tools/live-check.mjs"], {
  encoding: "utf8",
  env: process.env
});

const ok = run.status === 0;
const tail = `${run.stdout ?? ""}${run.stderr ?? ""}`.trim().split("\n").slice(-4).join("\n");

mkdirSync("dist", { recursive: true });
writeFileSync(
  "dist/watch.json",
  `${JSON.stringify({ startedAt, base, ok, exitCode: run.status, tail }, null, 2)}\n`
);

console.log(ok ? `watch: live check passed (${base})` : `watch: live check FAILED (${base})`);
console.log(tail);

const hook = process.env.WATCH_WEBHOOK;
if (!ok && hook) {
  try {
    const response = await fetch(hook, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ text: `GlucoPilot live check failed\n${base}\n\n${tail}` })
    });
    console.log(`watch: webhook responded ${response.status}`);
  } catch (error) {
    console.log(`watch: webhook failed — ${error.message}`);
  }
} else if (!ok) {
  console.log("watch: set WATCH_WEBHOOK to be notified, e.g. a Slack or Discord webhook URL");
}

process.exit(ok ? 0 : 1);
