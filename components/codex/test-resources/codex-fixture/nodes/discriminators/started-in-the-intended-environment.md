---
type: discriminator
id: started-in-the-intended-environment
peg: 5
board: process-stuck-or-slow
confidence: high
origin: owned
horizon: tactical
routes-to:
  - "no": environment-isolation
  - "yes": liveness-detection
---
----
Status:: #discriminator
Tags::[[Thesium Codex]] [[board]]

----

# Peg 5 · Did the process start with the environment you intended?

*Inheritance is invisible at the call site and its symptoms — exhaustion, overflow, mysterious slowness — appear nowhere near the cause.*

**Origin:: owned  ·  Horizon:: tactical**

## Answers
- **no** → [[environment-isolation]]
  It inherited more than you meant. Construct the child environment explicitly rather than filtering the parent's.
- **yes** → [[liveness-detection]]
  Then the problem is measurement: you cannot yet tell progress from activity, and that is the thing to fix first.
