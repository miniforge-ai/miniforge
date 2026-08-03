---
type: problem
id: contract-drift-is-silent
confidence: speculative
horizon: operational
origin: owned
support: 31
exposes:
  - "chained-work-identity"
open:
  - "when producer and consumer live in different repos, the verification has no single place to live"
scars: []
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# Nothing enforces agreement between a producer and its consumers

**Horizon:: operational**

## Forces in tension
- the consumer compiles against its assumption of the shape, not the producer's actual output
- a mismatched read returns nil or a fallback, not an error — the feature no-ops forever and reads as working
- specs and docs drift the same way code does, and direct implementers at keys that do not exist

## Resolution
Verify the read side against the producer's code — not your memory of it — every time either side changes. Prefer one shared definition of the boundary shape over two private ones, and make a failed lookup loud somewhere a human will see it.

## Exposes
- [[chained-work-identity]]

## Still open
- when producer and consumer live in different repos, the verification has no single place to live

## Anchored by

