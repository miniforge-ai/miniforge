<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(gate): the phase envelope reads gate results with the shared predicate

## Overview

`gate/passed?` accepts two result shapes: the legacy `{:passed? bool}` map and the
canonical `response/success` / `response/error` map. `decide/gates->envelope`
filtered results on the raw `:passed?` key instead. A canonical success has no
`:passed?` key, so the envelope counted it as a failure and denied the phase.
The `:format` gate returns that shape. The CLI printed `Gate :format passed` and
then failed the phase with `gate format failed: check failed` (#2376).

## Motivation

The implement phase's default gates include `:format`
(`phase-software-factory` `config/phase/defaults.edn`). The envelope conversion
arrived in #1558 and copied the raw-key check that commit b5f5586f9 had already
removed from `gate/passed?`. `execution_transition` filters gate failures with
`gate/passed?` and carries a comment warning against a bare `(remove :passed?)`.
`decide` was the one reader left.

## Changes in Detail

1. `gate.result` (new namespace): reads a gate result's shape. `passed?` moves
   here unchanged from `gate.interface`. `errors` returns the reported errors in
   either shape: the legacy `:errors` vector, or the single error a
   `response/error` result carries under `:error`.
2. `gate.interface/passed?` re-exports `gate.result/passed?`, so its callers are
   unchanged.
3. `decide/gates->envelope` selects mechanical failures with
   `gate.result/passed?`. Each reason takes its message from
   `gate.result/errors`, so a canonical error's message reaches the envelope
   instead of the generic `check failed`.

Left out: `capabilities/gate-failed?` and `gate-result->violation` keep their
own copy of the same shape logic. Their truth table already matches
`gate.result`, so they have no bug. `capabilities.clj` uses 7 strata on main, and
stratum-lint (SL003) refuses any edit to it until the namespace is split.

## Behaviour change

A phase whose gates all pass now gets an `:allow` envelope when one of those
gates returns the canonical success shape. Real failures in either shape still
deny. A canonical `response/error` reason now names the gate's own message.

## Testing Plan

1. `decide-test`: a canonical success result yields `:allow` with no reasons; a
   canonical error yields `:deny` with one `:reason/gate-check-failed` that
   carries the error's message.
2. `format-test`: `check-gates [:format]` followed by `gates->envelope` on the real
   format gate yields `:allow` with no reasons. This is the path from the issue.
3. Against main's `decide.clj`, these tests fail with five assertions. The
   envelope is `:deny` with the reason `gate format failed: check failed`,
   matching the issue.
4. Gate component namespaces (decide, format, capabilities, interface,
   policy-pack, validation-pipeline): 84 tests, 0 failures.

## Deployment Plan

No migration. Merge when green.

## Related Issues/PRs

Fixes #2376. Related: #2297 adds test-time interface schemas for gate results
and does not change this conversion.

## Checklist

- [x] Tests fail without the fix
- [ ] CI green, Copilot comments resolved
