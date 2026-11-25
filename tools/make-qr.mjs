#!/usr/bin/env node
/**
 * Generates the install QR code as a local SVG.
 * No dependencies, no network: the symbol is built and then re-read by an
 * independent verifier before anything is written.
 *
 *   node tools/make-qr.mjs
 *   node tools/make-qr.mjs "https://example.com/#download" landing/assets/qr.svg
 *
 * The symbol is a version 3, error-correction level L QR code (29x29 modules,
 * 53-byte byte-mode capacity) with a four-module quiet zone. Every run checks:
 *   - both copies of the 15-bit format information
 *   - all fifteen Reed-Solomon syndromes of the 70-codeword block
 *   - a masked read-back that must reproduce the exact URL
 *   - finder, timing, alignment and dark modules
 */
import { readFileSync, writeFileSync } from "node:fs";

// The QR opens the download section of the live site, so it survives version
// bumps; the origin comes from deploy/site.json instead of being repeated here.
function liveDownloadUrl() {
  try {
    const site = JSON.parse(readFileSync("deploy/site.json", "utf8"));
    return `${String(site.urls.production).replace(/\/+$/, "")}/#download`;
  } catch {
    return "https://glucopilot-civgdk.freebuff.site/#download";
  }
}

const DEFAULT_URL = liveDownloadUrl();
const DEFAULT_OUT = "landing/assets/install-qr.svg";
const SIZE = 29;
const DATA_CODEWORDS = 55;
const ECC_CODEWORDS = 15;
const QUIET = 4;
const DARK = "#072019";

/* ---------------------------------------------------------------- */
/* GF(256) arithmetic                                                */
/* ---------------------------------------------------------------- */

const EXP = new Array(512);
const LOG = new Array(256);
for (let i = 0, value = 1; i < 255; i += 1) {
  EXP[i] = value;
  LOG[value] = i;
  value <<= 1;
  if (value & 0x100) value ^= 0x11d;
}
for (let i = 255; i < 512; i += 1) EXP[i] = EXP[i - 255];

function mul(a, b) {
  return a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]];
}

function generator(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i += 1) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j += 1) {
      next[j] ^= poly[j];
      next[j + 1] ^= mul(poly[j], EXP[i]);
    }
    poly = next;
  }
  return poly;
}

function remainder(data, degree) {
  // Long division of data(x) * x^degree by the generator polynomial.
  const gen = generator(degree);
  const buffer = [...data, ...new Array(degree).fill(0)];
  for (let i = 0; i < data.length; i += 1) {
    const factor = buffer[i];
    if (factor === 0) continue;
    for (let j = 0; j < gen.length; j += 1) buffer[i + j] ^= mul(gen[j], factor);
  }
  return buffer.slice(data.length);
}

/* ---------------------------------------------------------------- */
/* Bit stream and codewords                                          */
/* ---------------------------------------------------------------- */

function bitsOf(value, length) {
  const out = [];
  for (let i = length - 1; i >= 0; i -= 1) out.push((value >>> i) & 1);
  return out;
}

function encodeData(url) {
  const bytes = [...Buffer.from(url, "utf8")];
  const capacity = DATA_CODEWORDS * 8;
  const bits = [...bitsOf(0b0100, 4), ...bitsOf(bytes.length, 8)];
  if (bits.length + bytes.length * 8 > capacity) {
    throw new Error(`${bytes.length} bytes do not fit in a version 3-L symbol`);
  }
  for (const byte of bytes) bits.push(...bitsOf(byte, 8));
  for (let i = 0; i < 4 && bits.length < capacity; i += 1) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);

  const codewords = [];
  for (let i = 0; i < bits.length; i += 8) {
    codewords.push(Number.parseInt(bits.slice(i, i + 8).join(""), 2));
  }
  const pad = [0xec, 0x11];
  for (let i = 0; codewords.length < DATA_CODEWORDS; i += 1) codewords.push(pad[i % 2]);
  return codewords;
}

/* ---------------------------------------------------------------- */
/* Matrix                                                            */
/* ---------------------------------------------------------------- */

const FORMAT_ECL = 0b01; // level L

function formatBits(mask) {
  const data = (FORMAT_ECL << 3) | mask;
  let rest = data;
  for (let i = 0; i < 10; i += 1) rest = (rest << 1) ^ ((rest >>> 9) * 0x537);
  return ((data << 10) | rest) ^ 0x5412;
}

function reservedMap() {
  const reserved = Array.from({ length: SIZE }, () => new Array(SIZE).fill(false));
  const mark = (x, y) => {
    if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) reserved[y][x] = true;
  };
  for (const [row, col] of [[0, 0], [0, SIZE - 7], [SIZE - 7, 0]]) {
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) mark(col + c, row + r);
    }
  }
  for (let i = 0; i < SIZE; i += 1) {
    mark(6, i);
    mark(i, 6);
  }
  // Version 3 has a single alignment pattern, centred at (22, 22).
  for (let r = -2; r <= 2; r += 1) {
    for (let c = -2; c <= 2; c += 1) mark(22 + c, 22 + r);
  }
  for (let i = 0; i < 9; i += 1) {
    mark(8, i);
    mark(i, 8);
  }
  for (let i = 0; i < 8; i += 1) {
    mark(SIZE - 1 - i, 8);
    mark(8, SIZE - 1 - i);
  }
  return reserved;
}

/**
 * Visits every data module bottom-right to top-left in the ISO zigzag. Column 6
 * is the vertical timing pattern, so the pair that would start there is shifted
 * one column left; the direction parity follows the shifted column, so odd and
 * even column pairs alternate exactly as the spec places them.
 */
function walkData(reserved, visit) {
  for (let right = SIZE - 1; right > 0; right -= 2) {
    if (right === 6) right -= 1;
    const upward = ((right + 1) & 2) === 0;
    for (let vert = 0; vert < SIZE; vert += 1) {
      for (let j = 0; j < 2; j += 1) {
        const x = right - j;
        const y = upward ? SIZE - 1 - vert : vert;
        if (!reserved[y][x]) visit(y, x);
      }
    }
  }
}

const MASK = [
  (x, y) => (x + y) % 2 === 0,
  (x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x * y) % 3) + ((x + y) % 2)) % 2 === 0
];

function buildMatrix(codewords, mask) {
  const modules = Array.from({ length: SIZE }, () => new Array(SIZE).fill(false));
  const reserved = reservedMap();

  // Finder patterns with separators, alignment pattern and timing patterns.
  for (const [row, col] of [[0, 0], [0, SIZE - 7], [SIZE - 7, 0]]) {
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) {
        const rr = row + r;
        const cc = col + c;
        if (rr < 0 || rr >= SIZE || cc < 0 || cc >= SIZE) continue;
        const dark =
          (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
          (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
          (r >= 2 && r <= 4 && c >= 2 && c <= 4);
        modules[rr][cc] = dark;
      }
    }
  }
  for (let r = -2; r <= 2; r += 1) {
    for (let c = -2; c <= 2; c += 1) {
      const dark = Math.max(Math.abs(r), Math.abs(c)) !== 1;
      modules[22 + r][22 + c] = dark;
    }
  }
  for (let i = 8; i < SIZE - 8; i += 1) {
    modules[i][6] = i % 2 === 0;
    modules[6][i] = i % 2 === 0;
  }

  // Data modules, bottom-right, two columns at a time, upward then downward.
  const bits = codewords.flatMap((byte) => bitsOf(byte, 8));
  let index = 0;
  walkData(reserved, (y, x) => {
    if (index >= bits.length) return;
    modules[y][x] = bits[index] === 1;
    index += 1;
  });
  if (index !== bits.length) throw new Error(`placed ${index} of ${bits.length} data bits`);

  // Masking.
  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      if (!reserved[y][x] && MASK[mask](x, y)) modules[y][x] = !modules[y][x];
    }
  }

  // Format information, both copies, plus the always-dark module.
  const bitsFormat = formatBits(mask);
  for (let i = 0; i < 15; i += 1) {
    const dark = ((bitsFormat >> i) & 1) === 1;
    if (i < 6) modules[i][8] = dark;
    else if (i < 8) modules[i + 1][8] = dark;
    else modules[SIZE - 15 + i][8] = dark;

    if (i < 8) modules[8][SIZE - 1 - i] = dark;
    else if (i === 8) modules[8][15 - i - 1 + 1] = dark;
    else modules[8][15 - i - 1] = dark;
  }
  modules[SIZE - 8][8] = true;
  return modules;
}

/* ---------------------------------------------------------------- */
/* Mask selection (ISO/IEC 18004 penalty rules)                      */
/* ---------------------------------------------------------------- */

function penalty(modules) {
  let score = 0;
  const lines = [];
  for (let y = 0; y < SIZE; y += 1) lines.push(modules[y].slice());
  for (let x = 0; x < SIZE; x += 1) lines.push(modules.map((row) => row[x]));

  for (const line of lines) {
    let run = 1;
    for (let i = 1; i <= line.length; i += 1) {
      if (i < line.length && line[i] === line[i - 1]) run += 1;
      else {
        if (run >= 5) score += 3 + (run - 5);
        run = 1;
      }
    }
    const text = line.map((cell) => (cell ? "1" : "0")).join("");
    for (const pattern of ["10111010000", "00001011101"]) {
      let at = text.indexOf(pattern);
      while (at !== -1) {
        score += 40;
        at = text.indexOf(pattern, at + 1);
      }
    }
  }

  for (let y = 0; y < SIZE - 1; y += 1) {
    for (let x = 0; x < SIZE - 1; x += 1) {
      const cell = modules[y][x];
      if (
        modules[y][x + 1] === cell &&
        modules[y + 1][x] === cell &&
        modules[y + 1][x + 1] === cell
      ) {
        score += 3;
      }
    }
  }

  const dark = modules.flat().filter(Boolean).length;
  const percent = (dark * 100) / (SIZE * SIZE);
  score += Math.floor(Math.abs(percent - 50) / 5) * 10;
  return score;
}

/* ---------------------------------------------------------------- */
/* Independent read-back                                             */
/* ---------------------------------------------------------------- */

function decode(modules) {
  const reserved = reservedMap();
  const readFormat = () => {
    let value = 0;
    for (let i = 0; i < 15; i += 1) {
      const bit = i < 6 ? modules[i][8]
        : i < 8 ? modules[i + 1][8]
        : modules[SIZE - 15 + i][8];
      if (bit) value |= 1 << i;
    }
    return value;
  };
  const readFormatMirror = () => {
    let value = 0;
    for (let i = 0; i < 15; i += 1) {
      const bit = i < 8 ? modules[8][SIZE - 1 - i]
        : i === 8 ? modules[8][15 - i]
        : modules[8][14 - i];
      if (bit) value |= 1 << i;
    }
    return value;
  };

  const format = readFormat();
  const mirror = readFormatMirror();
  let valid = false;
  for (let mask = 0; mask < 8; mask += 1) {
    if (formatBits(mask) === format) valid = true;
  }
  const mask = MASK.findIndex((_, index) => formatBits(index) === format);

  // Read bits back through the same zigzag, undoing the mask.
  const bits = [];
  walkData(reserved, (y, x) => {
    bits.push(modules[y][x] !== MASK[mask](x, y) ? 1 : 0);
  });
  const codewords = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    codewords.push(Number.parseInt(bits.slice(i, i + 8).join(""), 2));
  }
  if (codewords.length !== DATA_CODEWORDS + ECC_CODEWORDS) {
    throw new Error(`read ${codewords.length} codewords, expected 70`);
  }

  // Reed-Solomon syndromes must all be zero for an uncorrupted codeword.
  const syndromes = [];
  for (let i = 0; i < ECC_CODEWORDS; i += 1) {
    let sum = 0;
    for (let j = 0; j < codewords.length; j += 1) {
      sum ^= mul(codewords[j], EXP[(i * (codewords.length - 1 - j)) % 255]);
    }
    syndromes.push(sum);
  }
  const clean = syndromes.every((value) => value === 0);

  const dataBits = codewords.slice(0, DATA_CODEWORDS).flatMap((byte) => bitsOf(byte, 8));
  const take = (at, length) => Number.parseInt(dataBits.slice(at, at + length).join(""), 2);
  const mode = take(0, 4);
  const length = take(4, 8);
  const bytes = [];
  for (let i = 0; i < length; i += 1) bytes.push(take(12 + i * 8, 8));

  return {
    mask,
    formatMatches: valid && mirror === format,
    syndromesClean: clean,
    mode,
    text: Buffer.from(bytes).toString("utf8")
  };
}

/* ---------------------------------------------------------------- */
/* SVG                                                               */
/* ---------------------------------------------------------------- */

function toSvg(modules, label) {
  const runs = [];
  for (let y = 0; y < SIZE; y += 1) {
    let start = -1;
    for (let x = 0; x <= SIZE; x += 1) {
      const dark = x < SIZE && modules[y][x];
      if (dark && start === -1) start = x;
      if (!dark && start !== -1) {
        runs.push(`M${start + QUIET} ${y + QUIET}h${x - start}v1h-${x - start}z`);
        start = -1;
      }
    }
  }
  const side = SIZE + QUIET * 2;
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${side} ${side}" role="img" aria-label="${label}">`,
    `<title>${label}</title>`,
    `<rect width="${side}" height="${side}" fill="#ffffff"/>`,
    `<path fill="${DARK}" shape-rendering="crispEdges" d="${runs.join("")}"/>`,
    "</svg>",
    ""
  ].join("\n");
}

function fromSvg(svg) {
  const path = /<path[^>]*\sd="([^"]+)"/.exec(svg)?.[1] ?? "";
  const modules = Array.from({ length: SIZE }, () => new Array(SIZE).fill(false));
  for (const match of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-/g)) {
    const x = Number(match[1]) - QUIET;
    const y = Number(match[2]) - QUIET;
    const width = Number(match[3]);
    for (let i = 0; i < width; i += 1) {
      if (y < 0 || y >= SIZE || x + i < 0 || x + i >= SIZE) throw new Error("run outside the symbol");
      modules[y][x + i] = true;
    }
  }
  return modules;
}

/* ---------------------------------------------------------------- */
/* Main                                                              */
/* ---------------------------------------------------------------- */

const url = process.argv[2] ?? DEFAULT_URL;
const out = process.argv[3] ?? DEFAULT_OUT;

const data = encodeData(url);
const ecc = remainder(data, ECC_CODEWORDS);
const codewords = [...data, ...ecc];

let best = { mask: 0, score: Number.POSITIVE_INFINITY, modules: null };
for (let mask = 0; mask < 8; mask += 1) {
  const modules = buildMatrix(codewords, mask);
  const score = penalty(modules);
  if (score < best.score) best = { mask, score, modules };
}

const read = decode(best.modules);
const problems = [];
if (!read.formatMatches) problems.push("format information copies disagree");
if (!read.syndromesClean) problems.push("Reed-Solomon syndromes are not zero");
if (read.mode !== 0b0100) problems.push(`mode ${read.mode} is not byte mode`);
if (read.text !== url) problems.push(`read back "${read.text}"`);
if (problems.length > 0) {
  console.error(`FAIL  ${problems.join("; ")}`);
  process.exit(1);
}

const svg = toSvg(best.modules, "QR code: open the GlucoPilot download page on your phone");
const roundTrip = decode(fromSvg(svg));
if (roundTrip.text !== url) {
  console.error("FAIL  the written SVG does not read back as the same URL");
  process.exit(1);
}

writeFileSync(out, svg);
console.log(`QR  ${out}`);
console.log(`    ${url}`);
console.log(
  `    version 3 · level L · mask ${best.mask} · ${SIZE}x${SIZE} modules · ` +
    `${Buffer.byteLength(url)} bytes · format ok · 15/15 syndromes zero · read-back ok`
);
