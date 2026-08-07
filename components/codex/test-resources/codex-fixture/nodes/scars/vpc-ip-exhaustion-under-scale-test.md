---
type: scar
id: vpc-ip-exhaustion-under-scale-test
date: 2025
cost: "cluster module redesign"
cost-horizon: operational
domain: Infrastructure
confidence: high
origin: owned
anchors:
  - capacity-headroom
evidence:
  - "career-history/2025_portfolio_review.md (Project 1)"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Infrastructure]]

----

# The scale test ran out of addresses, not compute

**Cost:: cluster module redesign  ·  2025  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
A Hermes scale-up test exhausted the VPC's IP space. The binding constraint was addressing, which no capacity model had accounted for, and the fix was a redesign of the cluster module.

## What it changed
Scale limits are rarely where the dashboard points. Enumerate every finite resource in the path, including the ones with no metric.

## Anchors
- [[capacity-headroom]]
