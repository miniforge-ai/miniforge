<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: buzzword-bingo card and session

## Overview

Third slice. #1716 prepared documents for counting, #1757 did the counting and
grading. This adds the game half: a five-by-five card dealt from the catalog,
and a session that plays hits onto it across the turns of one conversation.

The two halves answer different questions and neither replaces the other. The
score says *is this prose worth keeping* — a rate, comparable across documents,
suitable for a gate. The card says *how bad has this conversation got* — a
running picture of which distinct buzzwords have turned up, and a line to shout
BINGO on.

## Motivation

A density score is the right instrument for a gate and the wrong one for
watching a session. Repeating `robust` twenty times raises one number and tells
you nothing new; the card only marks that square once, so a completed line
means twenty *different* tells, which is a genuinely different observation.

## Changes in Detail

### `card.cljc`

Twenty-five squares drawn from the catalog, centre free.

Dealing is determined entirely by the seed: the same seed and catalog always
deal the same card, so a card can be rebuilt from its seed rather than stored,
and two people watching one session see the same board.

Order comes from hashing seed and term id together, with the id breaking ties
so the order is total. The hash uses a modulus small enough that every
intermediate product is exact in both a JVM long and a JavaScript double. That
is the one thing this file has to get right — a host disagreement would deal
two different cards from one seed, and the MCP server (Node) and the CLI
(Babashka) would then disagree about the same session.

Dealing does not depend on the order entries arrive in, so a reordered catalog
deals the same card. A catalog too small to fill a board is refused rather than
dealt with gaps.

### `session.cljc`

A session is a value. Each turn's scan folds into a new session rather than
mutating one, so whoever holds it decides where state lives — an atom in the
MCP server, a file for the CLI, nothing at all in a test.

Marking is by term id, so a word said five times marks one square. The twelve
winning lines are written out rather than computed: card geometry is worth
being able to read, and a literal cannot drift from the board it describes. The
free centre square counts toward both diagonals and the middle row and column,
as it does in the game.

`:session/new-lines` carries only the lines this turn completed. A caller
announcing BINGO reads that rather than `:session/lines`, so each line is
announced once however many turns follow it.

The session also accumulates turns, hits, weight and words, so a conversation
can be scored as a whole and not only turn by turn.

### `interface.cljc`

`deal-card`, `open-session`, `track` and `winning-lines`.

## Testing Plan

35 tests, 105 assertions across the component, of which the new ones are:

- `card-test` — a full board in index order, the centre square free and
  term-less, no term dealt twice, same seed dealing the same card, different
  seeds differing, a reordered catalog dealing the same card, and a
  too-small catalog refused.
- `session-test` — marks, counts and totals accumulating across turns; a turn
  with no hits still advancing; a line completing by row, by column and by
  diagonal with the free square supplying its fifth mark; four of five squares
  not completing; a line announced once and not again on a later turn; and a
  turn repeating already-marked terms announcing nothing.

### Cross-host verification

The whole component compiles to a Node script with `cljs.main --target node`,
and all 35 test namespaces pass under `cljs.test` with the same counts as
Babashka. The determinism the card depends on is therefore checked on both
hosts rather than assumed from one.

## Deployment Plan

Library code. No entry point; nothing consumes the card until the MCP server
and CLI arrive.

## Related Issues/PRs

1. #1716 — regex, phrase, segment strata (merged)
2. #1757 — catalog, detection, scoring (merged)
3. **This PR** — card and session
4. Report renderers and message catalogs
5. CLI and `bb bingo` task
6. MCP server
7. Stop hook and skill
8. ClojureScript/Node build and npm packaging

## Checklist

- [x] `poly check` clean, no warnings
- [x] `clj-kondo` clean
- [x] `stratum-lint` clean, both namespaces inside the three-layer budget
- [x] Each commit under the 200-line commit budget
- [x] Apache header on every source file
- [x] Adversarial self-review of the diff
- [x] Standards gap analysis against `standards/miniforge`
- [x] Determinism verified on both hosts, not inferred from one
