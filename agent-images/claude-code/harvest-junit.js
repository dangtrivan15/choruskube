#!/usr/bin/env node
// harvest-junit.js — turn a repo's JUnit XML into the index's "failing tests" section.
//
// Usage: node harvest-junit.js <reports-root>
// Prints markdown to stdout; prints NOTHING when there are no failures. Always exits 0 —
// this is reporting, and a harvest problem must never change the Test node's verdict.
//
// Node rather than a shell pipeline because the agent image ships neither python3 nor
// xmllint (see the Dockerfile's apt list), and regex-over-XML in bash across multi-line
// <failure> bodies is where this would quietly start losing failures.
const fs = require("fs");
const path = require("path");

// ---- Failure extraction policy ------------------------------------------------------
// Tuning knobs for the trade-off between the downstream agent's context budget and
// diagnostic completeness. Every failure is always NAMED; these bound only how much
// detail each one carries, and any truncation is announced explicitly — a silent cap
// would read as "these were the only failures", which is the failure mode to avoid.
const MAX_STACK_LINES = 10;
const MAX_DETAILED_FAILURES = 50;
// -------------------------------------------------------------------------------------

const root = process.argv[2];
if (!root) process.exit(0);

const ENTITIES = { lt: "<", gt: ">", amp: "&", quot: '"', apos: "'" };
function decode(s) {
  return String(s)
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)))
    .replace(/&(lt|gt|amp|quot|apos);/g, (_, e) => ENTITIES[e]);
}

function walk(dir, acc) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return acc;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.endsWith(".xml")) acc.push(p);
  }
  return acc;
}

// <testcase .../> or <testcase ...>…</testcase>; a failure/error child means it failed.
const TESTCASE = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
const PROBLEM = /<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/;
const attr = (s, n) => {
  const m = s.match(new RegExp(`\\b${n}="([^"]*)"`));
  return m ? decode(m[1]) : "";
};

const failures = [];
for (const file of walk(root, [])) {
  let xml;
  try {
    xml = fs.readFileSync(file, "utf8");
  } catch {
    continue;
  }
  // The component is the first path segment under the repo's report root, which is how
  // the report tree is laid out (<root>/api-server, <root>/web-ui, …).
  const component = path.relative(root, file).split(path.sep)[0] || "(root)";
  let m;
  TESTCASE.lastIndex = 0;
  while ((m = TESTCASE.exec(xml)) !== null) {
    const body = m[2] || "";
    const p = body.match(PROBLEM);
    if (!p) continue;
    failures.push({
      component,
      kind: p[1],
      cls: attr(m[1], "classname"),
      name: attr(m[1], "name"),
      message: attr(p[2] || "", "message"),
      stack: decode(p[3] || "").trim(),
    });
  }
}

if (failures.length === 0) process.exit(0);

const byComponent = new Map();
for (const f of failures) {
  if (!byComponent.has(f.component)) byComponent.set(f.component, []);
  byComponent.get(f.component).push(f);
}

const out = [];
let detailed = 0;
for (const [component, list] of [...byComponent].sort()) {
  out.push(`#### ${component} — ${list.length} failing`, "");
  for (const f of list) {
    out.push(`- **${f.cls || "(unknown class)"} › ${f.name || "(unnamed)"}**`);
    if (f.message) out.push(`  - ${f.message.replace(/\s*\n\s*/g, " ")}`);
    if (detailed < MAX_DETAILED_FAILURES && f.stack) {
      const lines = f.stack.split("\n");
      const shown = lines.slice(0, MAX_STACK_LINES);
      out.push("", "  ```", ...shown.map((l) => "  " + l.trim()));
      if (lines.length > MAX_STACK_LINES) {
        out.push(`  … ${lines.length - MAX_STACK_LINES} more stack lines`);
      }
      out.push("  ```", "");
    }
    detailed++;
  }
  out.push("");
}

if (failures.length > MAX_DETAILED_FAILURES) {
  out.push(
    `> ${failures.length} tests failed. All are named above; stacks are included for the`,
    `> first ${MAX_DETAILED_FAILURES} only.`,
    ""
  );
}

process.stdout.write(out.join("\n") + "\n");
