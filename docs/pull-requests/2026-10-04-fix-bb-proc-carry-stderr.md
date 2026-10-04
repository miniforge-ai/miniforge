<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Carry a failing command's stderr into the exception it throws

## Layer and dependency

`components/bb-proc`, Layer 0 (a pure helper) and Layer 1 (`run!`). No new
dependencies beyond `clojure.string`. Nothing else in the workspace changes.

## Scope

`run!` captures a command's stderr and then discards it. Every bang-caller in
every repository that uses this component reports a failure as an exit code and
an argv, with the reason the command gave thrown away.

Observed cost, in thesium: `Daily Pro Snapshot` failed on **30 consecutive
scheduled runs**, 2026-09-04 through 2026-10-03, and the weekly publish with it.
Every run logged exactly this and nothing more:

```
Message:  Command failed: ("aws" "s3" "cp" ".../dist/daily/dashboard_snapshot.json"
          "s3://***/daily/archive/2026-10-03-snapshot.json" ...)
Data:     {:exit 1, :cmd (...)}
```

The AWS CLI prints a specific reason on stderr for each of credentials,
permissions, bucket and endpoint. `p/sh` captured it. `run!` dropped it. A month
of identical failures carried no information about which of those it was, and
two different guesses at the cause from the surviving evidence were both wrong.

## Change

Layer 0 gains `captured-error`, which turns a process result's `:err` into
something worth reporting or nil:

- nil when the stream was not captured as a string. A caller overriding `:err`
  with `:inherit`, a file or a stream gets nil from `p/sh`, and the documented
  stdio overrides have to keep working.
- nil when the captured text is blank. An empty stream is nothing to report,
  not an empty report.
- long output keeps its **tail**, with `truncation-marker` standing in for the
  head so a truncated block cannot read as a complete one. A failing command
  states what went wrong at the end, and an exception is not the place to paste
  a build log. The marker is counted *within* `max-captured-error-chars` (4000),
  not added on top of it, so the value in `:err` never exceeds the bound its
  own docstring promises.

Layer 1's `run!` adds the text to both halves of the throw:

- `ex-data` gains `:err`, alongside the existing `:exit` and `:cmd`. Babashka's
  top-level handler prints `Data:`, which is how this reaches a CI log.
- the message gains it on a second line, which is what a handler printing only
  `ex-message` shows.

Both additions are `cond->`, so a failure with no captured stderr throws exactly
what it threw before. `:out` is deliberately not included: `aws`, `git` and
`cargo` all report errors on stderr, and stdout is where the large output lives.

## Evidence

Three tests, in `components/bb-proc/test`:

- `test-run!-carries-captured-stderr-into-the-failure` — a command that writes
  to stderr and exits 1; asserts the text is in `ex-data` and in the message.
  This fails on `main`.
- `test-run!-omits-stderr-when-there-is-none` — a failing command that writes
  nothing; asserts no `:err` key and a single-line message.
- `test-run!-tolerates-uncaptured-stderr` — `:err :inherit`; asserts it still
  throws with `:exit` and no `:err` key.
- `test-run!-truncates-long-stderr-within-the-bound` — 5011 characters of
  stderr ending in a recognisable tail; asserts the marker is present, the tail
  survives, and the whole value fits inside `max-captured-error-chars`.

The existing `test-run!-throws-on-nonzero-exit` continues to assert `:exit` and
`:cmd`, so the established contract is pinned by a test that did not change.

**Not run here.** This change was authored in a cloud session with no Babashka
toolchain, and installing one fails: `repo.clojars.org` returns 403 through the
session's proxy. `bb test` has to run on review. The tests above use only `sh`,
`true` and `false`, matching the constraint the test namespace already states.

## Consequence

The next failing `run!` says what the command said. For the thesium publish
specifically, the following run prints the AWS CLI's own error, which decides
between a mis-wired credential, a token whose scope does not cover the write,
and a bucket or endpoint mismatch — none of which can be told apart from an
exit code.
