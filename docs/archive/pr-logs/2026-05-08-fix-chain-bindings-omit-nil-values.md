<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix chain bindings omit nil values

## Summary

Stop Miniforge workflow chains from materializing missing optional
inputs as present-with-`nil` keys during step handoff.

This surfaced in Thesium Career on the JD/rank path: Career correctly
omitted `:career/bootstrap-company-notes` when no company notes were
provided, but Miniforge's chain runner reintroduced the key with a
`nil` value while resolving `:step/input-bindings`. The downstream
workflow schema treats that field as optional-string, so the presence
of `nil` caused rank-step validation failure.

## Changes

- `components/workflow/src/ai/miniforge/workflow/chain.clj`
  - change `resolve-bindings` to skip bindings that resolve to `nil`
    instead of associating `key -> nil`
- `components/workflow/test/ai/miniforge/workflow/chain_test.clj`
  - add a regression test that proves missing optional bindings are
    omitted from the resolved step input

## Why

Chain bindings should preserve the semantic difference between:

- key absent
- key present with a concrete value

For optional workflow inputs, converting "absent" into
"present-with-nil" breaks boundary validation and leaks transport
mechanics into application contracts.

## Verification

- `clojure -M:test -e "(require 'ai.miniforge.workflow.chain-test) (let [result (clojure.test/run-tests 'ai.miniforge.workflow.chain-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`

## Result

Workflow chains now preserve omitted optional inputs correctly across
step handoff, which fixes the generic nil-materialization bug for
Career and any other workflow family depending on optional chain input
bindings.
