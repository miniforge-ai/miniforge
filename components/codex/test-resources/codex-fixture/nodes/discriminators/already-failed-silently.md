---
type: discriminator
id: already-failed-silently
peg: 3
board: process-stuck-or-slow
confidence: high
origin: owned
horizon: tactical
routes-to:
  - "yes": infra-vs-domain-failure
  - "no": consuming-an-earlier-steps-output
---
----
Status:: #discriminator
Tags::[[Thesium Codex]] [[board]]

----

# Peg 3 · Did something already fail, but get reported as ordinary output?

*A failure that is typed as content travels downstream unchallenged and surfaces as a stall far from its cause.*

**Origin:: owned  ·  Horizon:: tactical**

## Answers
- **yes** → [[infra-vs-domain-failure]]
  A timeout read as a finding, an error string read as a result. Fix the type at the boundary, not the symptom here.
- **no** → [[consuming-an-earlier-steps-output]]
  Nothing failed and nothing is moving — first suspect what the step was supposed to be reading.
