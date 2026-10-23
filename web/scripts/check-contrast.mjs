#!/usr/bin/env node
/**
 * WCAG AA gate for the token palette (ADR-0012).
 *
 * Parses the token blocks out of app/globals.css so the script cannot drift
 * from the stylesheet, then asserts the text/background pairs the UI actually
 * uses. Normal-size text must reach 4.5:1 in both themes.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, "..", "app", "globals.css"), "utf8");

function block(marker) {
  const start = css.indexOf(marker);
  if (start < 0) throw new Error(`token block not found: ${marker}`);
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const body = css.slice(open + 1, close);
  const tokens = new Map();
  for (const match of body.matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    tokens.set(match[1], match[2].trim());
  }
  return tokens;
}

const dark = block(":root {");
const light = new Map([...dark, ...block('[data-theme="light"] {')]);

/** Semantic tokens alias primitives through var(); resolve down to a hex. */
function resolve(tokens, name, seen = new Set()) {
  if (seen.has(name)) return undefined;
  seen.add(name);
  const value = tokens.get(name);
  if (!value) return undefined;
  const alias = /^var\(--([\w-]+)\)$/.exec(value);
  return alias ? resolve(tokens, alias[1], seen) : value.toLowerCase();
}

function channel(value) {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const AA = 4.5;
const backgroundTokens = ["surface-0", "surface-1", "surface-2"];
const foregroundTokens = ["text-primary", "text-secondary", "text-muted"];

const pairs = [];
for (const bg of backgroundTokens) {
  for (const fg of foregroundTokens) {
    pairs.push({ fg, bg, min: AA });
  }
}
pairs.push({ fg: "text-on-accent", bg: "accent-fill", min: AA });
pairs.push({ fg: "accent", bg: "surface-0", min: AA });
pairs.push({ fg: "text-primary", bg: "bubble-user", min: AA });
pairs.push({ fg: "text-primary", bg: "bubble-assistant", min: AA });
for (const status of ["danger", "success", "warning"]) {
  pairs.push({ fg: status, bg: "surface-1", min: AA });
}

let failed = 0;
for (const [theme, tokens] of [
  ["dark", dark],
  ["light", light],
]) {
  for (const { fg, bg, min } of pairs) {
    const foreground = resolve(tokens, fg);
    const background = resolve(tokens, bg);
    if (!foreground || !background) {
      console.error(`missing token: ${fg} or ${bg} (${theme})`);
      failed += 1;
      continue;
    }
    const ratio = contrast(foreground, background);
    const pass = ratio >= min;
    if (!pass) failed += 1;
    console.log(
      `${pass ? "PASS" : "FAIL"} ${theme.padEnd(5)} ${fg} on ${bg}: ${ratio.toFixed(2)}:1 (min ${min})`,
    );
  }
}

if (failed > 0) {
  console.error(`\n${failed} contrast pair(s) below AA`);
  process.exit(1);
}
console.log("\nall token pairs meet WCAG AA");
