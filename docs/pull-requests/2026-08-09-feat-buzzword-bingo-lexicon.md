<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: buzzword-bingo catalog, detection and scoring

## Overview

Second slice of Buzzword Bingo. #1716 landed the strata that prepare a document
for counting; this one does the counting. After it, `scan` takes prose and
returns hits with positions, per-category and per-term tallies, a rate, and a
grade of `:clean`, `:suspect` or `:slop`.

That is the whole gate. Everything after this PR is a surface onto it: the
bingo card, the CLI, the MCP server, the Stop hook and the npm build.

## Motivation

Generated prose has a register — marketing adjectives that assert quality
instead of stating behaviour, corporate filler, and words that mark a text as
machine-written. This repository bans that register by hand in
`standards/miniforge` and in the author's own instructions. Nothing measured
it, so enforcement was argument rather than evidence.

## Changes in Detail

### `entry.cljc` — compiling an authored entry

An authored entry carries a term (or raw pattern source) and a category. The
scanner needs a stable id, a display name, the weight in force and a compiled
pattern. A catalog is authored rather than received at runtime, so an unknown
category or an entry with neither term nor label throws at load rather than
being handled as data.

`compile-all` is public so a caller can supply a project-local catalog in the
same shape and get entries the scanner accepts.

### `inline.clj` — compile-time EDN

ClojureScript has no runtime classpath, so `io/resource` is unavailable there.
Reading the EDN during macro expansion — which runs on the JVM for both targets
— keeps the catalog canonical as reviewable data on disk while emitting it as a
literal into the compiled output.

Build tools that cache macro expansion do not know this macro reads a file, so
the consuming namespace needs listing under shadow-cljs `:cache-blockers` when
that build arrives.

### `lexicon.edn` — the catalog

Around ninety terms across five categories, weighted by how damning each is:

| Category | Default weight | What it covers |
|---|---|---|
| `:marketing` | 3 | Selling adjectives — a claim about how good a thing is, in place of what it does |
| `:ai-tell` | 3 | Register that marks generated text |
| `:corporate` | 2 | Business jargon |
| `:hedge` | 1 | Qualifiers that soften without informing |
| `:filler` | 1 | Intensifiers |

Weights are not uniform inside a category. `delve`, `tapestry`,
`a testament to` and `ever-evolving` carry 5, because each is close to a
fingerprint on its own.

EDN so a disagreement about a word is a one-line data change, not a code
change. Terms and pattern labels name the thing being detected rather than
being display copy, so they stay out of the message catalogs.

### `locate.cljc` and `detect.cljc` — finding hits

Matching runs over the masked, case-folded copy so code and links cannot score;
every reported line, column and quoted snippet comes from the source. Both
strings have the same length by construction, so one offset addresses both.

Overlapping entries are counted once. `deep dive` contains `dive`, and charging
a document for both double-counts one phrase. Hits sort earliest-first and
longest-first at equal position, so every kept hit starts at or before the
candidate; a candidate ending no later than the furthest end so far is
contained in one of them and is dropped. Partial overlaps are kept — those are
two phrases, not one counted twice.

### `tally.cljc` and `score.cljc` — grading

A raw count is not comparable across documents, so the figure is weighted hits
per thousand words. Every rate carries 100 smoothing words in the denominator:
without it a two-sentence reply with one buzzword outscores a long document
with twenty.

The smoothing damps a short text without exempting it. A lone filler word in a
ten-word reply scores 9 and stays clean; a marketing adjective in the same
reply scores 27 and is flagged. Catching that second case is the point of the
tool.

Per-category and per-term tallies come alongside the score, because a number
says whether to rewrite and the tallies say which words to reach for first.

## Calibration

Thresholds of 10 (suspect) and 25 (slop) are set by hand, not derived. The
evidence they are set against:

| Sample | Score | Grade |
|---|---|---|
| `readme.md` | 3.58 | clean |
| `agents.md` | 0.81 | clean |
| `CONTRIBUTING.md` | 0.00 | clean |
| `ROADMAP.md` | 2.51 | clean |
| `standards/miniforge/foundations/simple-made-easy.mdc` | 0.00 | clean |
| A plain change report | 0.00 | clean |
| Prose with mild drift (`robust`, `leverages`, `actually`, `circle back`) | 62.02 | slop |
| A generated marketing paragraph | 280.00 | slop |

Masking is what keeps the repository documents clean — they are full of code
blocks and paths. Treat the numbers the way this repository treats its
review-size budgets: a working figure, tuned as evidence arrives.

## Testing Plan

29 tests, 75 assertions, run on the JVM through `poly test` and under Babashka.

- `entry-test` — id derivation from term and from label, weight falling back to
  category and being overridden per entry, and both load-time rejections.
- `lexicon-test` — invariants over the shipped catalog: every entry compiles to
  a pattern, weights positive, ids unique so tallies cannot collide, every
  category declared. Reaching the assertions at all proves the EDN compiled.
- `detect-test` — line and column against the source, position unshifted by
  masking, context quoted, hits ordered by position, containment counted once,
  partial overlap counted twice, code and URLs not counted, word count over
  prose only.
- `score-test` — grade at each boundary, caller thresholds replacing defaults,
  short-text damping in both directions, category and term tallies.
- `interface-test` — end to end through the shipped catalog: a plain change
  report clean with nothing flagged, a generated pitch slop, buzzwords in code
  not charged, hits carrying everything needed to act on them.

### Cross-host verification

The component compiles to a Node script with `cljs.main --target node`, and the
same `.cljc` test namespaces pass under `cljs.test`. Babashka and Node produce
byte-identical output on the same input — same grade, same value to two
decimals, same hits with the same line and column, same tallies.

Recorded for whoever compiles a Polylith component to ClojureScript next:
`interface` is a reserved JavaScript keyword, so Closure munges the namespace
to `interface$` and warns. Harmless, and it will fire for every component.

## Deployment Plan

Library code. No entry point and no runtime behaviour change to any existing
project; nothing consumes `scan` until the CLI arrives.

## Related Issues/PRs

Sequence, each branching from `main` after the previous merges:

1. #1716 — regex, phrase, segment strata (merged)
2. **This PR** — catalog, detection, scoring
3. Bingo card and session accumulation
4. Report renderers and message catalogs
5. CLI and `bb bingo` task
6. MCP server
7. Stop hook and skill
8. ClojureScript/Node build and npm packaging

## Checklist

- [x] `poly check` clean, no warnings
- [x] `clj-kondo` clean
- [x] `stratum-lint` clean; four namespaces split to stay inside the three-layer
      budget (`entry` from `lexicon`, `locate` from `detect`, `tally` from
      `score`)
- [x] Each commit under the 200-line commit budget; whole PR 499 of 600
- [x] Apache header on every source file
- [x] Adversarial self-review of the diff
- [x] Standards gap analysis against `standards/miniforge`
- [x] Calibrated against real documents, not only synthetic samples
