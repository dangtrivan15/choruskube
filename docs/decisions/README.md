# Decisions

Design decisions that outlived the run that made them.

A feature run's spec is a run artifact: it is written, used, and discarded. A decision
graduates into this directory when something in the repo cites it and a later reader
would otherwise be left guessing why the code is shaped the way it is. Everything else
stays in the spec and goes away with it.

## Files

One file per run, named `YYYY-MM-DD---NN-<slug>.md` — the date the run landed, `NN`
ordering runs that landed on the same date, and `<slug>` naming the change. That single
file holds every decision the run graduates; a re-run edits it rather than adding a
second one.

**An entry is immutable once merged.** A later decision that reverses or replaces an
earlier one does not edit it. Write the new entry, name in it which entry it supersedes,
and mark the old entry's index row `superseded by <new entry>`. What was believed at the
time, and what changed it, is the whole value of the record — rewriting an entry destroys
both.

Cite an entry by filename. Never by an ordinal from the spec it came from: those numbers
are scoped to one run and mean something different in the next one.

## Index

Every entry gets a row, newest last.

| Entry | Date | Decides | Status |
|---|---|---|---|

- **Entry** — the filename, linked.
- **Date** — the date in the filename.
- **Decides** — one line: the question the entry settles.
- **Status** — `current`, or `superseded by <entry>`.
