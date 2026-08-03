---
type: scar
id: keepalive-masked-stagnation
date: 2026-06
cost: "false stagnation kills"
cost-horizon: operational
domain: Agent runtime
confidence: high
origin: owned
anchors:
  - liveness-detection
evidence:
  - "code: components/llm/src/ai/miniforge/llm/progress_monitor.clj:48,141"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Agent runtime]]

----

# Heartbeats counted as progress

**Cost:: false stagnation kills  ·  2026-06  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
Stagnation detection treated any stream activity as progress, so spinner and keepalive blips reset the clock. Real stalls went undetected; the recorded incident reads 'Stagnation timeout: no progress for 180047ms'.

## What it changed
Liveness must be measured on substantive progress — streamed content plus filesystem writes — not on the presence of bytes. A keepalive is evidence the transport is alive, not that the work is.

## Anchors
- [[liveness-detection]]
