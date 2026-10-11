# Decisions

Design decisions that outlived the run that made them.

A feature run's spec is a run artifact: it is written, used, and discarded. A decision
graduates into this directory when something in the repo cites it and a later reader
would otherwise be left guessing why the code is shaped the way it is. Everything else
stays in the spec and goes away with it.

## Files

One file per run, named `YYYY-MM-DD---NN-<slug>.md` — the UTC date the entry is written, `NN` ordering the entries
written on that date from `01`, and `<slug>` naming the change. That single file holds every decision the run
graduates; a re-run edits it rather than adding a second one. Listed by name, the entries run in the order they were
written, and each title says what the entry decides.

The date is the one the writer knows: the date a run lands is not known when its entry is written. It is UTC so that
entries written in different timezones still sort in the order they were written. An entry can only supersede one
already on the base branch, so the superseding entry always sorts after the one it replaces.

`NN` is the next free number for that date on the base branch. Runs land in parallel, so another open branch can take
the same number, and git merges the two files without a word because their names differ. After merging the base
branch into yours, check that no two entries share a date and number:

```sh
ls docs/decisions | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}---[0-9]{2}-' | cut -c1-15 | sort | uniq -d
```

If it prints a stem, keep the entry already on the base branch, because it is merged and cited. Move yours to the next
free number for that date with `git mv`, and update every reference to its old filename.

Cite an entry by filename. Never by an ordinal from the spec it came from: those numbers
are scoped to one run and mean something different in the next one.

## Status

Each entry states its status on the line under its title:

```markdown
**Status:** current
```

**An entry is immutable once merged, except its status line.** A later decision that reverses or replaces an earlier
one does not edit the earlier entry's body. Write the new entry, name in it which entry it supersedes, and rewrite the
old entry's status line to say what is superseded and by which entry: all of it
(`**Status:** superseded by [<new entry>](<new entry>.md)`) or a part
(`**Status:** current; its <part> is superseded by [<new entry>](<new entry>.md)`). What was believed at the time, and
what changed it, is the whole value of the record — rewriting an entry's body destroys both.

The status sits in the entry, not in a list beside it or only in the entry that supersedes it: a reader who arrives
from a code comment sees that the entry no longer holds without a second lookup. To see every entry's status at once
(a status that wraps shows its first line):

```sh
grep -H '^\*\*Status:\*\*' docs/decisions/2*.md | sort
```

There is no index file. A table that every run appends a row to conflicts between any two open PRs that each add an
entry, and everything it held is in the filenames, the titles and the status lines.
