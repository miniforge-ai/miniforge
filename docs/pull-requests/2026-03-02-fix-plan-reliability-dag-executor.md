# Fix Plan Phase Reliability & Universal DAG Executor

**Branch:** `fix/plan-reliability-dag-executor`
**Base:** `feat/nimble-sdlc-explore-phase`
**Date:** 2026-03-02

## Summary

Fixes plan phase reliability issues discovered during dogfooding of
`finish-yc-mvp.spec.edn` (spec #5), where the MCP artifact server
returned `nil`, parse fallback failed, and the system silently continued
with a useless stub plan. Also promotes the DAG executor to the universal
execution path for all plans and extracts the MCP artifact server into a
proper Polylith component.

## Motivation

During dogfooding, three failures compounded silently:

1. MCP artifact server returned `nil` (server didn't start or `submit_plan` was never called)
2. `parse-plan-response` couldn't extract EDN from conversational LLM output
3. `make-fallback-plan` silently created a 1-task stub and continued as if planning succeeded

The user directive was clear: "If they both fail, we should actually have a failure. With evidence."

## Changes

### Track 1: MCP artifact server → Polylith component

- **New component** `components/mcp-artifact-server/` with proper Polylith structure:
  - `protocol.clj` — JSON-RPC 2.0 read/write helpers
  - `tools.clj` — Data-driven tool registry (submit_plan, submit_code, submit_test, submit_release)
  - `server.clj` — Main stdin/stdout JSON-RPC loop
  - `main.clj` — Entry point for `bb -m` invocation
  - `interface.clj` — Public API: `start-server`, `handle-tool-call`, `tool-definitions`
  - `interface_test.clj` — Unit tests for all tool handlers, protocol, and validation
- **Updated** `artifact_session.clj`:
  - `resolve-server-script` now prefers the component, throws on resolution failure (no silent fallback)
  - `read-artifact` logs warnings on missing file or parse failure instead of swallowing exceptions
- **Updated** `scripts/test-mcp-artifact-server.bb` to prefer component over legacy script
- **Updated** root `deps.edn` with component in `:dev` and `:test` aliases

### Track 2: Remove silent fallback — fail with evidence

- **Removed** `make-fallback-plan` from `planner.clj`
- Plan generation now throws `ex-info` with evidence map when both MCP artifact and EDN parse fail:
  - `:phase`, `:mcp-artifact`, `:parse-result`, `:llm-content-length`, `:llm-content-preview`
- LLM failure branch returns `response/error` without a fallback plan
- No-LLM-client branch throws instead of silently creating a stub

### Track 3: Universal DAG executor in interceptor pipeline

- **Updated** `execution.clj` to route all plans through the DAG executor after the plan phase
- Added helpers: `extract-plan-from-phase-result`, `index-after-phase`, `try-dag-execution`
- DAG executor handles both parallel and sequential plans (no `parallelizable?` gate)
- DAG success skips the implement phase; DAG failure transitions to failed
- Opt-out via `:disable-dag-execution` context flag

### Track 4: Spec-provided plan/tasks bypass

- **Updated** `plan.clj` `enter-plan` to detect `:plan/tasks` in spec input
- When present, builds plan directly from spec tasks (no LLM call, 0 tokens)
- Falls through to normal planner invocation when no spec tasks provided

## Files Changed

| File | Type | Description |
|------|------|-------------|
| `components/mcp-artifact-server/**` | New | Polylith component for MCP artifact server |
| `components/agent/.../artifact_session.clj` | Modified | Fix script resolution, add logging, fix error swallowing |
| `components/agent/.../planner.clj` | Modified | Remove fallback, throw with evidence |
| `components/phase/.../plan.clj` | Modified | Spec-provided plan/tasks fast path |
| `components/workflow/.../execution.clj` | Modified | Universal DAG executor after plan phase |
| `deps.edn` | Modified | Add mcp-artifact-server component |
| `scripts/test-mcp-artifact-server.bb` | Modified | Prefer component over legacy script |

## Verification

1. `bb test:all` — no regressions
2. Run `finish-yc-mvp.spec.edn` — spec has `:plan/tasks`, should skip LLM planning, route to DAG executor
3. Test plan failure evidence — mock LLM returning garbage, verify exception with evidence (not silent stub)
4. Test MCP server component — unit tests for tool handlers + JSON-RPC protocol
5. Test DAG in interceptor pipeline — all plans route through DAG execution
