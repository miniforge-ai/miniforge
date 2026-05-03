# feat/direct-workflow-agent-supervisory-projection

## Summary

This PR makes direct in-process workflow agents visible to supervisory-state and
downstream clients.

Before this slice, live workflow runs emitted fresh `:supervisory/workflow-*`
snapshots but still produced **zero** `AgentSession` entities unless they went
through the separate control-plane orchestrator path. The TUI could therefore
show current runs while still rendering empty agent panes and sparse dossiers.

This PR bridges that gap by mirroring direct `agent/invoke` calls into the
existing `:control-plane/agent-*` event family that supervisory-state already
projects.

## Why

The control surface needs a single authoritative source for agent sessions and
their status history. Supervisory-state already builds that from
`control-plane/agent-registered`, `control-plane/agent-heartbeat`, and
`control-plane/agent-state-changed`.

The product gap was not in supervisory-state. It was that ordinary workflow
phases still invoked internal agents directly and never emitted that event
family.

Using the existing control-plane vocabulary keeps the producer congruent with
the spec and avoids inventing a second agent projection path just for internal
workflow agents.

## What Changed

- added
  [supervisory_bridge.clj](../../components/agent/src/ai/miniforge/agent/supervisory_bridge.clj)
  to:
  - derive deterministic per-invocation agent session ids
  - emit `:control-plane/agent-registered`
  - emit `:control-plane/agent-heartbeat`
  - emit `:control-plane/agent-state-changed`
  - attach richer metadata using the vocabulary the clients already consume:
    - `:workflow-spec`
    - `:workflow-phase`
    - `:agent-context`
    - `:phase-context`
    - `:model`
- updated
  [runtime.clj](../../components/agent/src/ai/miniforge/agent/interface/runtime.clj)
  so `agent/invoke` routes through the new bridge when the invocation context
  is bound to a workflow event stream
- added
  [runtime_supervisory_projection_test.clj](../../components/agent/test/ai/miniforge/agent/runtime_supervisory_projection_test.clj)
  covering:
  - control-plane event emission from direct workflow-bound `agent/invoke`
  - derived `:supervisory/agent-upserted` snapshots
  - preservation of run/spec/phase/task metadata

## Behavior Change

- direct workflow agents now appear as real `AgentSession` entities in
  supervisory-state
- those sessions transition through `:executing` to `:completed` or `:failed`
  without requiring the external control-plane orchestrator
- clients consuming supervisory snapshots can now show miniforge-run agents
  during live runs instead of empty agent panes

## What This PR Does Not Do

- it does not yet enrich policy-derived attention
- it does not yet create new decision or PR producer paths
- it does not change external non-miniforge agent discovery

Those remain separate slices.

## Validation

- `clj-kondo --lint` on touched agent files
- `clojure -M:dev:test` with:
  - `ai.miniforge.agent.runtime-supervisory-projection-test`
  - `ai.miniforge.agent.interface-test`
  - `ai.miniforge.workflow.runner-test`
  - `ai.miniforge.supervisory-state.attention-test`
