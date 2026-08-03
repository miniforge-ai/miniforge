---
type: scar
id: fsm-swallowed-unknown-events
date: 2026-06
cost: "silent misrouting"
cost-horizon: operational
domain: Workflow engine
confidence: high
origin: owned
anchors:
  - fail-closed-by-default
evidence:
  - "code: components/fsm/src/ai/miniforge/fsm/core.clj:94,173,305 (:anomalies/fsm-unknown-event)"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Workflow engine]]

----

# The state machine ignored events it did not recognise

**Cost:: silent misrouting  ·  2026-06  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
clj-statecharts silently dropped state-local unknown events. Transitions that should have been impossible simply did not happen, and nothing reported it.

## What it changed
Silent downgrade is the disease. An unknown input is a typed anomaly, never a no-op — the cheapest possible failure is the one that says so.

## Anchors
- [[fail-closed-by-default]]
