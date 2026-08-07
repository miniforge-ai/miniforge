---
type: discriminator
id: reports-activity-while-stuck
peg: 2
board: process-stuck-or-slow
confidence: high
origin: owned
horizon: tactical
routes-to:
  - "yes": liveness-detection
  - "no": already-failed-silently
---
----
Status:: #discriminator
Tags::[[Thesium Codex]] [[board]]

----

# Peg 2 · Is it reporting activity while making no progress?

*This is where most stall diagnosis goes wrong: activity is read as health, and the monitoring agrees with the process rather than checking it.*

**Origin:: owned  ·  Horizon:: tactical**

## Answers
- **yes** → [[liveness-detection]]
  Heartbeats, spinners and keepalives are evidence the transport lives, not the work. Your liveness signal is measuring the wrong thing.
- **no** → [[already-failed-silently]]
  Silence could be death, or it could be a failure that was swallowed upstream.
