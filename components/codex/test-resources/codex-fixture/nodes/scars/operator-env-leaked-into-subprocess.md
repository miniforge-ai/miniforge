---
type: scar
id: operator-env-leaked-into-subprocess
date: 2026-06-07
cost: "200k context overflow"
cost-horizon: tactical
domain: Agent runtime
confidence: high
origin: owned
anchors:
  - environment-isolation
evidence:
  - "memory: project_dogfood_findings_2026_06_07"
---
----
Status:: #scar
Tags::[[Thesium Codex]] [[scar]] [[Agent runtime]]

----

# The planner inherited the operator's whole environment

**Cost:: 200k context overflow  ·  2026-06-07  ·  Origin:: owned  ·  Cost-horizon:: tactical**

## What happened
The planner's subprocess inherited the operator's MCP and config environment, dragging in enough context to overflow a 200k window before the planner had done anything.

## What it changed
A subprocess inherits more than you intend by default. Isolate explicitly (--strict-mcp-config, CLAUDE_CONFIG_DIR) rather than assuming a clean room.

## Anchors
- [[environment-isolation]]
