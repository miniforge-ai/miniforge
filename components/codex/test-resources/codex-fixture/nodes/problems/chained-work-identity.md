---
type: problem
id: chained-work-identity
confidence: high
horizon: operational
exposes: []
open:
  - "open design question: correct PR base for chained DAG tasks"
scars:
  - "stacked-pr-based-on-local-branch"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# Downstream steps can only reference what exists in the shared namespace

**Horizon:: operational**

## Forces in tension
- local and shared identifiers are often textually identical
- the producing step succeeds; only the consumer fails
- failure lands far from the cause, usually in a later phase

## Resolution
Anything a later step will reference must be published before that step runs, and the reference must name the published thing. Resolve identity through the shared namespace even when a local one would work.

## Exposes
- nothing recorded yet

## Still open
- open design question: correct PR base for chained DAG tasks

## Anchored by
- [[stacked-pr-based-on-local-branch]]
