---
type: problem
id: liveness-detection
confidence: high
horizon: operational
exposes: []
open:
  - "progress is domain-specific; there is no universal signal"
scars:
  - "watchdog-timeout-incoherence"
  - "keepalive-masked-stagnation"
---
----
Status:: #problem
Tags::[[Thesium Codex]] [[problem]]

----

# Liveness cannot be inferred from activity

**Horizon:: operational**

## Forces in tension
- bytes on a stream prove the transport is alive, not that work is progressing
- a wall-clock timeout kills slow-but-healthy work; no timeout hangs forever
- every watcher you add is another thing that can kill healthy work

## Resolution
Measure substantive progress — streamed content plus filesystem writes — not the presence of traffic. Record keepalives explicitly as non-substantive. Where two watchers observe one signal, declare their ordering as an invariant in the source, not in someone's head.

## Exposes
- nothing recorded yet

## Still open
- progress is domain-specific; there is no universal signal

## Anchored by
- [[watchdog-timeout-incoherence]]
- [[keepalive-masked-stagnation]]
