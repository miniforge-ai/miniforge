---
type: scar
id: stacked-pr-based-on-local-branch
date: 2026-06
cost: "hard-killed release phase"
cost-horizon: operational
domain: PR automation
confidence: high
origin: owned
anchors:
  - chained-work-identity
evidence:
  - "PR #1102"
  - "open design question: DAG task PR base convention"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[PR automation]]

----

# A dependent PR was based on a branch that existed only locally

**Cost:: hard-killed release phase  ·  2026-06  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
Dependent DAG tasks opened PRs against the parent task's LOCAL branch rather than the pushed PR branch. The fetch failed and took the release phase down with it.

## What it changed
Anything a downstream step references must exist in the shared namespace, not the local one. 'It works on the machine that made it' is a class of bug, not an excuse.

## Anchors
- [[chained-work-identity]]
