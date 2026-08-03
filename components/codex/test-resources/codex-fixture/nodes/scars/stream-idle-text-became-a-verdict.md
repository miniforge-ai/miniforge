---
type: scar
id: stream-idle-text-became-a-verdict
date: 2026-06-05
cost: "failed gate on healthy work"
cost-horizon: operational
domain: Agent runtime
confidence: high
origin: owned
anchors:
  - infra-vs-domain-failure
evidence:
  - "code: components/agent/src/ai/miniforge/agent/result_boundary.clj:82-111 (canonical taxonomy :stream-idle / :stagnation / :hard-limit)"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Agent runtime]]

----

# A timeout message was read as review content

**Cost:: failed gate on healthy work  ·  2026-06-05  ·  Origin:: owned  ·  Cost-horizon:: operational**

## What happened
The reviewer LLM stream-idled at 360s. The timeout's text was promoted into :review/blocking-issues and failed the gate as though the reviewer had found real problems with the change.

## What it changed
Infrastructure failures must be normalised at the boundary where they occur, or they masquerade as domain output downstream. A failure and a finding are different types and must not share a channel.

## Anchors
- [[infra-vs-domain-failure]]
