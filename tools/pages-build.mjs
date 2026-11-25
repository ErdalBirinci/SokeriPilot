import fs from "node:fs";
import path from "node:path";

// Stages landing/ for GitHub Pages, which serves a project site from a subpath
// (https://<user>.github.io/SokeriPilot/). The Freebuff deployment keeps serving
// the same files from the domain root, so the source stays root-absolute and the
// prefix is added here, at publish time, instead of being baked into every page.

const BASE = "/SokeriPilot/";
const root = process.cwd();
const source = path.join(root, "landing");
const out = path.resolve(root, process.argv[2] ?? "_site");

if (fs.existsSync(out)) fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(source, out, { recursive: true });

// _headers is a Cloudflare/Freebuff file; Pages ignores it and would serve it as text.
fs.rmSync(path.join(out, "_headers"), { force: true });
fs.writeFileSync(path.join(out, ".nojekyll"), "");

const policy = /Content-Security-Policy:\s*(.+)/.exec(
  fs.readFileSync(path.join(source, "_headers"), "utf8")
)?.[1];
if (!policy) throw new Error("no Content-Security-Policy in landing/_headers");
// A meta policy cannot express frame-ancestors, so that one directive stays header-only.
const metaPolicy = policy.replace(/\s*frame-ancestors[^;]*;?/, "").trim();

const files = [];
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else files.push(full);
  }
};
walk(out);

let pages = 0;
for (const file of files) {
  if (!/\.(html|css)$/i.test(file)) continue;
  let text = fs.readFileSync(file, "utf8");
  const before = text;
  text = text.replace(/(href|src)="\//g, `$1="${BASE}`);
  if (file.endsWith(".html")) {
    if (text.includes('http-equiv="Content-Security-Policy"')) {
      throw new Error(`${file} already carries a CSP meta tag`);
    }
    text = text.replace(
      /(<meta charset="utf-8">)/i,
      `$1\n  <meta http-equiv="Content-Security-Policy" content="${metaPolicy}">`
    );
    pages += 1;
  }
  if (text !== before) fs.writeFileSync(file, text);
}

// Nothing may still point at the domain root, or it would escape /SokeriPilot/.
const escaped = files
  .filter((file) => /\.(html|css)$/i.test(file))
  .filter((file) => /(href|src)="\/(?!SokeriPilot\/)/.test(fs.readFileSync(file, "utf8")));
if (escaped.length) {
  throw new Error(`root-absolute paths left in: ${escaped.map((f) => path.relative(out, f)).join(", ")}`);
}

for (const required of ["index.html", "404.html", "download/index.html", "install/index.html", "privacy/index.html"]) {
  if (!fs.existsSync(path.join(out, required))) throw new Error(`missing ${required}`);
}

console.log(`pages: ${pages} html files prepared for ${BASE} (meta CSP, no _headers)`);
