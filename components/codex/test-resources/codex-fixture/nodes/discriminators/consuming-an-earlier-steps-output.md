---
type: discriminator
id: consuming-an-earlier-steps-output
peg: 4
board: process-stuck-or-slow
confidence: high
origin: owned
support: 21
horizon: tactical
routes-to:
  - "yes": contract-drift-is-silent
  - "no": started-in-the-intended-environment
---
----
Status:: #discriminator
Tags::[[Thesium Codex]] [[board]]

----

# Peg 4 · Is the silent step consuming something an earlier step was supposed to produce?

*A consumer reading a key its producer never writes gets nil, not an error — the feature no-ops forever, and from the outside a no-op loop is indistinguishable from a stall.*

**Origin:: owned  ·  Horizon:: tactical**

## Answers
- **yes** → [[contract-drift-is-silent]]
  Verify the read side against the producer's code before diagnosing anything else. The stall may be a contract mismatch wearing a stall's clothes.
- **no** → [[started-in-the-intended-environment]]
  It consumes nothing upstream — suspect the starting conditions.
