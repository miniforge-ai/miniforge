---
type: problem
id: environment-isolation
confidence: high
horizon: tactical
exposes: []
open: []
scars:
  - "operator-env-leaked-into-subprocess"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# A subprocess inherits more than you intend

**Horizon:: tactical**

## Forces in tension
- inheritance is the default and is invisible at the call site
- the parent's environment grows over time; the child's assumptions do not
- symptoms appear as resource exhaustion, far from the cause

## Resolution
Construct the child environment explicitly rather than filtering the parent's. Where the runtime offers strict or isolated config modes, use them by default and treat inheritance as the exception that must be justified.

## Exposes
- nothing recorded yet

## Still open
- none recorded

## Anchored by
- [[operator-env-leaked-into-subprocess]]
