---
type: scar
id: autodev-governance-leak
date: 2026
cost: "unauthorised PR reached a repo"
cost-horizon: strategic
domain: Agent authority
confidence: high
origin: owned
anchors:
  - authority-outside-the-model
  - fail-closed-by-default
evidence:
  - "ws/mvp-ramp/SYSTEM_BRIEFS.md (war story 2)"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Agent authority]]

----

# Fail-open config let an agent act on a repo it was not enabled for

**Cost:: unauthorised PR reached a repo  ·  2026  ·  Origin:: owned  ·  Cost-horizon:: strategic**

## What happened
An agent fixed a bug in a repository where it had not been enabled. The meta-agent caught it, but the PR had already gone out. Root cause was a configuration default that failed open when enablement was absent.

## What it changed
Absence of a grant is not permission. Every enablement check defaults to deny, and 'no configuration found' is a deny with a reason code, not a shrug.

## Anchors
- [[authority-outside-the-model]]
- [[fail-closed-by-default]]
