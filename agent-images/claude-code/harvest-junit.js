#!/usr/bin/env node
// harvest-junit.js — turn a repo's JUnit XML into the index's "failing tests" section.
//
// Usage: node harvest-junit.js <reports-root>
// Prints markdown to stdout; prints NOTHING when there are no failures and every report
// file was read and fully parsed. Always exits 0 — this is reporting, and a harvest
// problem must never change the Test node's verdict.
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
// Report files that exist but can't be turned into data. An absent report TREE is the
// expected shape for a repo that hasn't produced one yet and must stay silent (handled by
// the caller, which never invokes this script without a directory); a file that IS there
// and comes back empty-handed is different, and gets counted instead of read as a silent
// "no failures".
let unparseable = 0;
for (const file of walk(root, [])) {
  let xml;
  try {
    xml = fs.readFileSync(file, "utf8");
  } catch {
    unparseable++;
    continue;
  }
  // CDATA content is literal text, not markup — a <system-out> capturing raw command
  // output (a logged request payload, a printed stack trace) can contain the substring
  // "<failure" and get matched as a real one below. Strip CDATA bodies first so only
  // actual elements are visible to the regexes that follow.
  xml = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, "");
  // The component is the first path segment under the repo's report root, which is how
  // the report tree is laid out (<root>/api-server, <root>/web-ui, …).
  const component = path.relative(root, file).split(path.sep)[0] || "(root)";
  const rawTestcaseCount = (xml.match(/<testcase\b/g) || []).length;
  let m;
  TESTCASE.lastIndex = 0;
  let matched = 0;
  while ((m = TESTCASE.exec(xml)) !== null) {
    matched++;
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
  // A <testcase that never closes — no `/>`, no matching </testcase> — is what a process
  // killed mid-write leaves behind. Same principle as the read failure above: count it
  // rather than silently reading the file as having no failures.
  if (matched < rawTestcaseCount) unparseable++;
}

if (failures.length === 0 && unparseable === 0) process.exit(0);

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

if (unparseable > 0) {
  out.push(
    `> ${unparseable} report file(s) could not be read or fully parsed and were skipped; ` +
      `see test-output.log for the full run.`,
    ""
  );
}

process.stdout.write(out.join("\n") + "\n");
