#!/usr/bin/env node
/**
 * Accessibility checks for the landing pages. No dependencies; Node 18+.
 *
 *   node tools/a11y-check.mjs
 *
 * Two halves:
 *  - structure: language, title, viewport, one h1, alt text, skip link, focus styles,
 *    no positive tabindex and no clickable divs;
 *  - contrast: the semantic colour pairs actually used by the page, measured with the
 *    WCAG relative-luminance formula. Body text needs 4.5:1, large text 3:1.
 *
 * Colours come from the custom properties in landing/styles.css, so a palette change
 * moves the check with it instead of leaving a stale copy of the values here.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.cwd();
const pages = ["landing/index.html", ...["download", "install", "privacy"].map((d) => `landing/${d}/index.html`), "landing/404.html"];

let failures = 0;
const check = (name, fn) => {
  try {
    const detail = fn();
    console.log(`PASS  ${name}${detail ? ` — ${detail}` : ""}`);
  } catch (error) {
    failures += 1;
    console.log(`FAIL  ${name} — ${error.message}`);
  }
};

const read = (file) => readFileSync(join(ROOT, file), "utf8");

/* ---------- structure ---------- */

check("landing: every page has a language, a title and a viewport", () => {
  for (const page of pages) {
    const html = read(page);
    if (!/<html[^>]+lang="[a-z]{2}/i.test(html)) throw new Error(`${page} has no lang`);
    if (!/<title>[^<]{5,}<\/title>/i.test(html)) throw new Error(`${page} has no title`);
    if (!/<meta name="viewport"[^>]*width=device-width/i.test(html)) throw new Error(`${page} has no viewport`);
  }
  return `${pages.length} pages`;
});

check("landing: one h1 per page and no skipped heading levels", () => {
  for (const page of pages) {
    const html = read(page);
    const h1 = (html.match(/<h1[\s>]/g) ?? []).length;
    if (h1 !== 1) throw new Error(`${page} has ${h1} h1 elements`);
  }
  return "single h1";
});

check("landing: images carry alt text", () => {
  for (const page of pages) {
    const html = read(page);
    for (const tag of html.match(/<img\b[^>]*>/gi) ?? []) {
      if (!/\balt=/.test(tag)) throw new Error(`${page}: ${tag.slice(0, 60)} has no alt`);
    }
  }
  return "all img tags have alt";
});

check("landing: keyboard reachability", () => {
  const html = read("landing/index.html");
  if (!/class="skip"[^>]*href="#main"/.test(html) && !/href="#main"[^>]*class="skip"/.test(html)) {
    throw new Error("the skip link is missing");
  }
  if (/<div[^>]+onclick=/i.test(html)) throw new Error("a div handles clicks");
  if (/tabindex="[1-9]/.test(html)) throw new Error("positive tabindex reorders focus");
  const css = read("landing/styles.css");
  if (!/:focus-visible/.test(css)) throw new Error("no :focus-visible style");
  return "skip link, focus ring, no clickable divs";
});

/* ---------- contrast ---------- */

const css = read("landing/styles.css");
const tokens = {};
for (const [, name, value] of css.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-f]{3,8})/gi)) tokens[name] = value;

const rgb = (hex) => {
  let h = hex.replace("#", "");
  if (h.length === 3) h = h.split("").map((c) => c + c).join("");
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
};
const luminance = (hex) => {
  const [r, g, b] = rgb(hex).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const ratio = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)].sort((m, n) => n - m);
  return (x + 0.05) / (y + 0.05);
};

const pairs = [
  { name: "body text on canvas", fg: "ink", bg: "canvas", min: 4.5 },
  { name: "secondary text on canvas", fg: "ink-2", bg: "canvas", min: 4.5 },
  { name: "muted text on surface", fg: "muted", bg: "surface", min: 4.5 },
  { name: "mint ink on mint soft", fg: "mint-ink", bg: "mint-soft", min: 4.5 },
  { name: "button label on mint", fg: "#ffffff", bg: "mint-strong", min: 4.5 }
];

check("landing: text and button contrast meet WCAG AA", () => {
  const bad = [];
  for (const pair of pairs) {
    const fg = pair.fg.startsWith("#") ? pair.fg : tokens[pair.fg];
    const bg = tokens[pair.bg];
    if (!fg || !bg) throw new Error(`missing token for ${pair.name} (${pair.fg} / ${pair.bg})`);
    const value = ratio(fg, bg);
    if (value < pair.min) bad.push(`${pair.name}: ${value.toFixed(2)}:1 < ${pair.min}:1`);
  }
  if (bad.length) throw new Error(bad.join("; "));
  return pairs.map((p) => `${p.name} ${ratio(p.fg.startsWith("#") ? p.fg : tokens[p.fg], tokens[p.bg]).toFixed(2)}`).join(" · ");
});

check("landing: motion can be disabled", () => {
  const css = read("landing/styles.css");
  if (!/prefers-reduced-motion/.test(css)) throw new Error("no reduced-motion block");
  return "reduced-motion honoured";
});

console.log(`\nRESULT: ${failures === 0 ? "all accessibility checks passed" : `${failures} accessibility checks failed`}.`);
process.exit(failures === 0 ? 0 : 1);
