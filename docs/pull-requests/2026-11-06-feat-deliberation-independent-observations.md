<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-harness): the independent selector sees its candidates' observations

## Overview

In the `independent` condition each candidate takes one turn on a fresh
private board, and the closing selector starts from a fresh board with only
the candidates' solutions and rationales. An observation a candidate
requested is attached to that candidate's board after its only turn, so no
request in the condition ever showed one. Since #2346 credits only what the
closing turn's board showed, the condition could never earn evidence credit.

Before the selector's closing turn, the trusted fixture reader now attaches
every observation the candidates' boards attest to the selector's fresh
board, through the same admission path as any other observation.

## Motivation

The operator asked that the independent condition see the observations. This
keeps the condition's design: candidates stay isolated from each other, the
selector still receives only solutions and rationales as handoff, and the
turn allowance is unchanged. The alternative, a budgeted second turn per
candidate, would change the condition's allowance.

## Changes in Detail

- `experiment/board-for`: the board a turn acts on. For the independent
  closing selector, it is a fresh board plus the union of the candidates'
  attested observations, each attached by `turn/attach-observation`, in a
  stable order. Every other turn uses its own board as before.
- `take-turn` uses `board-for`. The selector's recorded board carries the
  fixture reader's admitted transactions, so `verify` replays it.
- Atlas README: the comparison table and the evidence paragraph describe the
  new behaviour.

## Testing Plan

- `experiment_test/the-independent-selector-sees-its-candidates-observations`:
  candidates inspect every experiment and the selector inspects none. The
  closing request shows each observation's statement, and the cell is
  evidence-complete and succeeds. It fails on the previous code.
- All 5 harness suites: 48 tests, 164 assertions, 0 failures.
- A full offline smoke run of the development manifest: 60 cells, and
  `verify` replays all 510 activations. `independent` now scores 3/3 with the
  mock instead of 0/3.
- clj-kondo (CI version): 0 errors. SL006 stratum check: 0 mismatches.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox). `poly test` runs in CI.

## Deployment Plan

None. Development lab only.

## Related Issues/PRs

- Resolves the open design question in #2346's PR doc and the atlas README
  (#2349).

## Checklist

- [x] Candidates stay isolated; allowance unchanged
- [x] New behaviour has a test that fails on the previous code
- [x] Copyright header on the PR doc
