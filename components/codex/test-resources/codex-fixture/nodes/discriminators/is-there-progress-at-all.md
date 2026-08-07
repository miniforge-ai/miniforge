---
type: discriminator
id: is-there-progress-at-all
peg: 1
board: process-stuck-or-slow
confidence: high
origin: owned
horizon: tactical
routes-to:
  - "slow but moving": capacity-headroom
  - "no progress": reports-activity-while-stuck
---
----
Status:: #discriminator
Tags::[[Thesium Codex]] [[board]]

----

# Peg 1 · Is there progress at all, or none?

*Slow and stuck are different failures with different causes, and the instinct is to treat both as 'performance'.*

**Origin:: owned  ·  Horizon:: tactical**

## Answers
- **slow but moving** → [[capacity-headroom]]
  Something finite is binding. Find it before optimising anything.
- **no progress** → [[reports-activity-while-stuck]]
  Now the question is whether the process knows it is stuck.
