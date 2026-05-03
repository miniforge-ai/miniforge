# feat/policy-attention-context

## Summary

This PR makes policy-derived supervisory context materially more useful for
clients.

Before this slice, failed policy evaluations carried the raw violation data in
state, but the retained `PolicyEvaluation` and derived `AttentionItem` shapes
did not preserve enough correlation or summary detail for operators. The TUI
ended up showing rows like “Policy violation: 2 rule(s) failed” with little or
no indication of what failed, where it belonged, or what run it affected.

This PR preserves workflow and gate context on `PolicyEvaluation` and upgrades
derived policy attention summaries to name the gate, target, and first failing
rule/message.

## Why

The operator surface needs dense, actionable context without forcing every
client to reverse-engineer it from raw gate events.

The canonical place to retain that context is the producer-side supervisory
projection:

- `PolicyEvaluation` should remember which run and gate produced it
- `AttentionItem` should surface human-meaningful policy summaries derived from
  the canonical evaluation record

That keeps clients thin and consistent.

## What Changed

- extended
  [schema.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj)
  so `PolicyEvaluation` explicitly allows:
  - `:policy-eval/workflow-run-id`
  - `:policy-eval/gate-id`
- updated
  [accumulator.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj)
  so `:gate/passed` and `:gate/failed` projections now retain:
  - the originating workflow run id
  - the gate id
  - the existing target and violation payload
- updated
  [attention.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/attention.clj)
  so policy-derived attention now:
  - preserves run/gate/target context on the attention entity
  - summarizes the first failing rule and message
  - includes a `(+N more)` suffix when multiple violations exist
- added regression coverage in:
  - [accumulator_test.clj](../../components/supervisory-state/test/ai/miniforge/supervisory_state/accumulator_test.clj)
  - [attention_test.clj](../../components/supervisory-state/test/ai/miniforge/supervisory_state/attention_test.clj)

## Behavior Change

A failed policy evaluation now retains enough producer-side context for clients
to render something like:

`Policy violation in review-approved for workflow output bundle-42: review-body-required — Review body is missing (+1 more)`

instead of just:

`Policy violation: 2 rule(s) failed`

## What This PR Does Not Do

- it does not add new policy UI in clients by itself
- it does not yet introduce new decision request shapes
- it does not change PR or task projection paths

This is the producer-side context slice that makes those client surfaces possible.

## Validation

- `clj-kondo --lint` on touched supervisory-state files
- `clojure -M:dev:test` with:
  - `ai.miniforge.supervisory-state.accumulator-test`
  - `ai.miniforge.supervisory-state.attention-test`
