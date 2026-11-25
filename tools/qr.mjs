#!/usr/bin/env node
/**
 * Reader for the install QR code on the landing page.
 *
 *   node tools/qr.mjs [landing/assets/install-qr.svg]
 *
 * The asset is a version 3, level-L byte-mode symbol (29x29 modules, four-module
 * quiet zone). Re-encoding is a once-per-origin-change job; what has to keep
 * working is the read side, so this module only decodes. It is imported by
 * `tools/release-check.mjs` and `tools/live-check.mjs`, which compare the
 * payload with `deploy/site.json` — a stale or hand-edited symbol then fails the
 * release instead of shipping.
 *
 * Decoding covers both copies of the format information, the mask, the zigzag
 * data modules, the fifteen Reed-Solomon syndromes and the finder/timing
 * patterns. No dependencies, no network.
 *
 * If the site origin changes, regenerate the asset for `<origin>/#download`
 * (any QR generator, same version 3/L byte mode) and re-run the checks.
 */
import { pathToFileURL } from "node:url";
import { readFileSync } from "node:fs";

export const QR_SIZE = 29;
const QUIET = 4;
const DATA_CODEWORDS = 55;
const ECC_CODEWORDS = 15;
const FORMAT_ECL = 0b01; // level L

/* ---------------------------------------------------------------- */
/* GF(256) arithmetic, for the error-correction syndromes            */
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

function formatBits(mask) {
  const data = (FORMAT_ECL << 3) | mask;
  let rest = data;
  for (let i = 0; i < 10; i += 1) rest = (rest << 1) ^ ((rest >>> 9) * 0x537);
  return ((data << 10) | rest) ^ 0x5412;
}

const MASKS = [
  (x, y) => (x + y) % 2 === 0,
  (x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x * y) % 3) + ((x + y) % 2)) % 2 === 0
];

/* ---------------------------------------------------------------- */
/* Module layout                                                     */
/* ---------------------------------------------------------------- */

function reservedMap() {
  const reserved = Array.from({ length: QR_SIZE }, () => new Array(QR_SIZE).fill(false));
  const mark = (x, y) => {
    if (x >= 0 && x < QR_SIZE && y >= 0 && y < QR_SIZE) reserved[y][x] = true;
  };
  for (const [row, col] of [[0, 0], [0, QR_SIZE - 7], [QR_SIZE - 7, 0]]) {
    for (let r = -1; r <= 7; r += 1) {
      for (let c = -1; c <= 7; c += 1) mark(col + c, row + r);
    }
  }
  for (let i = 0; i < QR_SIZE; i += 1) {
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
    mark(QR_SIZE - 1 - i, 8);
    mark(8, QR_SIZE - 1 - i);
  }
  return reserved;
}

/**
 * Visits data modules bottom-right to top-left in the ISO zigzag. Column 6 is
 * the vertical timing pattern, so the pair that would start there shifts one
 * column left and the direction parity follows the shifted column.
 */
function walkData(reserved, visit) {
  for (let right = QR_SIZE - 1; right > 0; right -= 2) {
    if (right === 6) right -= 1;
    const upward = ((right + 1) & 2) === 0;
    for (let vert = 0; vert < QR_SIZE; vert += 1) {
      for (let j = 0; j < 2; j += 1) {
        const x = right - j;
        const y = upward ? QR_SIZE - 1 - vert : vert;
        if (!reserved[y][x]) visit(y, x);
      }
    }
  }
}

function bitsOf(value, length) {
  const out = [];
  for (let i = length - 1; i >= 0; i -= 1) out.push((value >>> i) & 1);
  return out;
}

/* ---------------------------------------------------------------- */
/* SVG -> modules                                                    */
/* ---------------------------------------------------------------- */

function matrixFromSvg(svg) {
  const path = /<path[^>]*\sd="([^"]+)"/.exec(svg)?.[1];
  if (!path) throw new Error("install-qr.svg has no module path");
  const modules = Array.from({ length: QR_SIZE }, () => new Array(QR_SIZE).fill(false));
  let runs = 0;
  for (const match of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-/g)) {
    const x = Number(match[1]) - QUIET;
    const y = Number(match[2]) - QUIET;
    const width = Number(match[3]);
    for (let i = 0; i < width; i += 1) {
      if (y < 0 || y >= QR_SIZE || x + i < 0 || x + i >= QR_SIZE) {
        throw new Error("the QR path draws outside the symbol");
      }
      modules[y][x + i] = true;
    }
    runs += 1;
  }
  if (runs === 0) throw new Error("install-qr.svg has an empty module path");
  return modules;
}

function structureOk(modules) {
  const finderAt = (row, col) => {
    for (let r = 0; r < 7; r += 1) {
      for (let c = 0; c < 7; c += 1) {
        const edge = r === 0 || r === 6 || c === 0 || c === 6;
        const core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
        if (modules[row + r][col + c] !== (edge || core)) return false;
      }
    }
    return true;
  };
  if (!finderAt(0, 0) || !finderAt(0, QR_SIZE - 7) || !finderAt(QR_SIZE - 7, 0)) return false;
  for (let i = 8; i < QR_SIZE - 8; i += 1) {
    if (modules[6][i] !== (i % 2 === 0)) return false;
    if (modules[i][6] !== (i % 2 === 0)) return false;
  }
  return true;
}

/* ---------------------------------------------------------------- */
/* Decoding                                                          */
/* ---------------------------------------------------------------- */

export function decodeQr(modules) {
  const reserved = reservedMap();

  let format = 0;
  for (let i = 0; i < 15; i += 1) {
    const bit = i < 6 ? modules[i][8]
      : i < 8 ? modules[i + 1][8]
      : modules[QR_SIZE - 15 + i][8];
    if (bit) format |= 1 << i;
  }
  let mirror = 0;
  for (let i = 0; i < 15; i += 1) {
    const bit = i < 8 ? modules[8][QR_SIZE - 1 - i]
      : i === 8 ? modules[8][15 - i]
      : modules[8][14 - i];
    if (bit) mirror |= 1 << i;
  }

  const mask = MASKS.findIndex((_, index) => formatBits(index) === format);
  if (mask === -1) throw new Error("the format information matches no valid mask");

  const bits = [];
  walkData(reserved, (y, x) => {
    bits.push(modules[y][x] !== MASKS[mask](x, y) ? 1 : 0);
  });
  const codewords = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    codewords.push(Number.parseInt(bits.slice(i, i + 8).join(""), 2));
  }
  if (codewords.length !== DATA_CODEWORDS + ECC_CODEWORDS) {
    throw new Error(`read ${codewords.length} codewords, expected ${DATA_CODEWORDS + ECC_CODEWORDS}`);
  }

  let syndromesOk = true;
  for (let i = 0; i < ECC_CODEWORDS && syndromesOk; i += 1) {
    let sum = 0;
    for (let j = 0; j < codewords.length; j += 1) {
      sum ^= mul(codewords[j], EXP[(i * (codewords.length - 1 - j)) % 255]);
    }
    syndromesOk = sum === 0;
  }

  const dataBits = codewords.slice(0, DATA_CODEWORDS).flatMap((byte) => bitsOf(byte, 8));
  const take = (at, length) => Number.parseInt(dataBits.slice(at, at + length).join(""), 2);
  const mode = take(0, 4);
  const length = take(4, 8);
  const bytes = [];
  for (let i = 0; i < length; i += 1) bytes.push(take(12 + i * 8, 8));

  return {
    text: Buffer.from(bytes).toString("utf8"),
    mode,
    mask,
    formatOk: mirror === format,
    syndromesOk,
    structureOk: structureOk(modules)
  };
}

export function decodeQrSvg(svg) {
  return decodeQr(matrixFromSvg(svg));
}

/* ---------------------------------------------------------------- */
/* CLI                                                               */
/* ---------------------------------------------------------------- */

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2] ?? "landing/assets/install-qr.svg";
  const qr = decodeQrSvg(readFileSync(file, "utf8"));
  console.log(`${file}\n  ${qr.text}`);
  console.log(
    `  version 3 · level L · mask ${qr.mask} · structure ${qr.structureOk ? "ok" : "BROKEN"}` +
      ` · format ${qr.formatOk ? "ok" : "BROKEN"} · syndromes ${qr.syndromesOk ? "zero" : "BROKEN"}`
  );
  if (qr.mode !== 0b0100 || !qr.structureOk || !qr.formatOk || !qr.syndromesOk) process.exit(1);
}
