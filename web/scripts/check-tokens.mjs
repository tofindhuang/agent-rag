#!/usr/bin/env node
/**
 * Guards ADR-0012: components consume semantic tokens, they never hard-code a
 * hex colour. globals.css is the only place allowed to define palette values.
 *
 * Ticket 01 records the current violations; ticket 06 requires this to pass.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = join(here, "..");
const roots = [join(webRoot, "components"), join(webRoot, "app")];
const HEX = /#[0-9a-fA-F]{3,8}\b/g;

function walk(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) files.push(...walk(path));
    else if (path.endsWith(".css") && !path.endsWith("globals.css")) files.push(path);
  }
  return files;
}

const violations = [];
for (const root of roots) {
  for (const file of walk(root)) {
    const lines = readFileSync(file, "utf8").split("\n");
    lines.forEach((line, index) => {
      const matches = line.match(HEX);
      if (!matches) return;
      violations.push({
        file: relative(webRoot, file),
        line: index + 1,
        colors: [...new Set(matches)].join(", "),
      });
    });
  }
}

if (violations.length === 0) {
  console.log("no hard-coded colours outside app/globals.css");
  process.exit(0);
}

for (const violation of violations) {
  console.log(`${violation.file}:${violation.line}  ${violation.colors}`);
}
console.error(
  `\n${violations.length} line(s) hard-code colours; use semantic tokens (ADR-0012)`,
);
process.exit(1);
