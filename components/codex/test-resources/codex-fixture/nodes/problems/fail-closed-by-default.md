---
type: problem
id: fail-closed-by-default
confidence: high
horizon: operational
exposes: []
open:
  - "fail-closed raises false-refusal cost; the tradeoff is real"
scars:
  - "fsm-swallowed-unknown-events"
  - "autodev-governance-leak"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# Absence of a signal is not permission

**Horizon:: operational**

## Forces in tension
- missing configuration, unknown events and empty results all look like 'nothing to do'
- failing open is always the cheaper implementation and usually the default
- the failure is invisible precisely because nothing happened

## Resolution
Every absence resolves to a deny with a reason code. Unknown inputs become typed anomalies rather than no-ops. 'No configuration found' is a decision the system announces, never a shrug it performs silently.

## Exposes
- nothing recorded yet

## Still open
- fail-closed raises false-refusal cost; the tradeoff is real

## Anchored by
- [[fsm-swallowed-unknown-events]]
- [[autodev-governance-leak]]
