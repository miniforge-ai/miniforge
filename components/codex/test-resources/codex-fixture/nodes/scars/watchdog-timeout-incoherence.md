---
type: scar
id: watchdog-timeout-incoherence
date: 2026-05
cost: "~17 hours"
cost-horizon: operational
domain: Agent runtime
confidence: high
origin: owned
anchors:
  - liveness-detection
  - revocation-at-commit
evidence:
  - "code: components/agent/src/ai/miniforge/agent/stream_watchdog.clj:49-67 (the invariant is now written into the source: watchdog 480s >= client idle 420s + 60s margin)"
  - "PRs #988, #989"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Agent runtime]]

----

# Two watchers of one stream with unordered timeouts

**Cost:: ~17 hours  ·  2026-05  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
A recurring 'stall' during retries. Every diagnosis pointed at locking. The actual cause was that the stream watchdog and the LLM client's stream-line timeout both watched the same stdout, with no ordering between them — the backstop was firing on healthy work.

## What it changed
A recurring stall is a timeout-coherence question before it is a locking question. Any two watchers of one signal need a declared ordering, or the outer one becomes a random killer.

## Anchors
- [[liveness-detection]]
- [[revocation-at-commit]]
