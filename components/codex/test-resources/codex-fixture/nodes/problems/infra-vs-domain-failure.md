---
type: problem
id: infra-vs-domain-failure
confidence: high
horizon: operational
exposes:
  - "fail-closed-by-default"
open: []
scars:
  - "stream-idle-text-became-a-verdict"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# Infrastructure failure must never present as domain output

**Horizon:: operational**

## Forces in tension
- both arrive on the same channel as text
- downstream consumers cannot tell a timeout message from a finding
- the failure is silent: it looks like the system worked and found problems

## Resolution
Normalise at the boundary where the failure occurs. Give infra failures a closed taxonomy of their own and a distinct type, so a timeout can never be read as a verdict no matter how far downstream it travels.

## Exposes
- [[fail-closed-by-default]]

## Still open
- none recorded

## Anchored by
- [[stream-idle-text-became-a-verdict]]
