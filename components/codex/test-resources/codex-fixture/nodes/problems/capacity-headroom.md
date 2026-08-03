---
type: problem
id: capacity-headroom
confidence: high
horizon: operational
exposes: []
open: []
scars:
  - "vpc-ip-exhaustion-under-scale-test"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# The binding constraint is rarely the one with a dashboard

**Horizon:: operational**

## Forces in tension
- capacity models cover the resources someone thought to measure
- the unmeasured finite resource is invisible until it is exhausted
- scale tests validate the model, not the reality outside it

## Resolution
Enumerate every finite resource in the request path, including those with no metric — addresses, file descriptors, connection-pool slots, quota ceilings. The ones without dashboards are the ones that will bind.

## Exposes
- nothing recorded yet

## Still open
- none recorded

## Anchored by
- [[vpc-ip-exhaustion-under-scale-test]]
