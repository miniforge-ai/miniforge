<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: buzzword-bingo reports and message catalog

## Overview

Fourth slice. The scanner produces data; this turns it into something a person
reads. Two renderers — a scan report and a session board — plus the message
catalog they draw their strings from and the number formatting they share.

Nothing here changes what is counted. It is the surface the CLI, the MCP server
and the Stop hook all print.

## Motivation

Every consumer that follows needs the same two pieces of text. Writing them
once, here, keeps the CLI and the MCP server from drifting into two dialects of
the same report — and keeps the strings in a catalog, where the localization
rule requires them, rather than scattered across three entry points.

## Changes in Detail

### `messages.cljc` and the catalog

The localization rule (dewey 050) is hard-halt: every emitted prose string
flows through a catalog. The shared `messages` component loads catalogs from
the classpath, which does not exist in a bundled Node script, so this component
carries its own loader using the same compile-time inlining as the lexicon.

Same `{placeholder}` convention and same `t` signature as the shared component,
so the two read alike at call sites. An absent key renders as its own name
rather than throwing: a missing string should degrade a report, not fail it.

Counts are pluralised by key — `-one` and `-many` — rather than by rule.
English needs only the two, and a locale needing more adds its own keys without
touching code.

### `format.cljc`

`clojure.core/format` does not exist in ClojureScript, and `str` on a double
disagrees across hosts: `25.0` prints as `"25.0"` on the JVM and `"25"` in
JavaScript. Either would show up as a diff between the Babashka CLI and the
Node build reporting the same scan, which is the property this component has
been protecting throughout. `decimal` renders two places identically on both;
`pad` and `truncate` hold the card's columns.

### `report.cljc`

The verdict first, the evidence after it. A reader who only wants the answer
stops at line one; a reader who disagrees can see every word that produced it,
along with the thresholds it was judged against and the catalog version that
produced the hits.

A clean scan says so in one line rather than printing empty sections. A long
tail of terms is cut at twelve with a count of the remainder — past that a
report stops being read and starts being scrolled.

### `board.cljc`

The card as a five-by-five grid, marked squares bracketed rather than coloured
so the board survives a pipe, a log file and a terminal without colour. Long
terms are truncated to keep every cell the same width, so the columns line up
whether a square is marked or not.

Progress counts the marks that landed *on this card*, not every term the
session has seen. Most of the catalog is not on any one board, and reporting
the wider number next to a 24-square total would overstate how full the card
is — a bug this PR found and fixed while writing the renderer.

## Testing Plan

21 tests, 78 assertions across these namespaces:

- `format-test` — the cross-host number cases that motivate the namespace,
  including a whole number rendering with two places on both hosts.
- `messages-test` — lookup, single and multiple placeholder substitution, the
  missing-key fallback, and plural selection at zero, one and many.
- `report-test` — the grade on the first line before any evidence, the score
  and thresholds and catalog version present, every flagged term named, a clean
  report staying short, and a caller-supplied catalog not being attributed to
  the shipped version.
- `board-test` — five rows, the free square shown as taken, marks visible
  without colour, progress counting card squares rather than every term seen,
  and BINGO announced only once a line is complete.

Bracketed cells are counted rather than matched by term, because a long term is
truncated to fit its cell and the full text is not in the output to look for.

## Deployment Plan

Library code. No entry point; the CLI is the first consumer.

## Related Issues/PRs

1. #1716 — regex, phrase, segment strata (merged)
2. #1757 — catalog, detection, scoring (merged)
3. #1767 — card and session
4. **This PR** — reports and message catalog
5. CLI and `bb bingo` task
6. MCP server
7. Stop hook and skill
8. ClojureScript/Node build and npm packaging

## Checklist

- [x] `poly check` clean, no warnings
- [x] `clj-kondo` clean
- [x] `stratum-lint` clean, every namespace inside the three-layer budget
- [x] Every emitted prose string routed through the catalog (dewey 050)
- [x] Each commit under the 200-line commit budget
- [x] Apache header on every source file
- [x] Adversarial self-review of the diff
- [x] Standards gap analysis against `standards/miniforge`
