# Stratified-Design Violations — Baseline (2026-05-01)

Audit of `components/**/src` and `bases/**/src` for within-namespace stratified-design violations. Run via `bb
tools/strata-audit.bb`.

## Summary

- **Files audited (with at least one violation):** 422
- **Total violations:** 4196
- **Components affected:** 83

## What counts as a violation?

A violation is an intra-namespace call from a function at Layer A to a function at Layer B where `B >= A`. In stratified
design, every same-namespace call must go strictly downward in layer. Same-layer composition and upward calls are both
rejected.

Layer assignment is determined by the most recent `;-- Layer N` comment marker preceding the function definition. The
audit uses `clj-kondo` analysis to build the call graph.

## By component (descending)

| Component | Files | Violations |
|---|---:|---:|
| `tui-views` | 35 | 701 |
| `workflow` | 31 | 348 |
| `web-dashboard` | 20 | 297 |
| `cli` | 34 | 228 |
| `pr-lifecycle` | 18 | 195 |
| `policy-pack` | 16 | 187 |
| `agent` | 17 | 169 |
| `loop` | 7 | 123 |
| `compliance-scanner` | 7 | 108 |
| `supervisory-state` | 5 | 81 |
| `lsp-mcp-bridge` | 9 | 79 |
| `phase-deployment` | 12 | 78 |
| `dag-executor` | 7 | 77 |
| `config` | 4 | 77 |
| `pr-sync` | 2 | 72 |
| `failure-classifier` | 2 | 69 |
| `knowledge` | 8 | 64 |
| `llm` | 4 | 62 |
| `reliability` | 6 | 61 |
| `repo-index` | 6 | 53 |
| `evidence-bundle` | 9 | 53 |
| `gate` | 9 | 45 |
| `phase-software-factory` | 5 | 45 |
| `tui-engine` | 8 | 44 |
| `event-stream` | 8 | 44 |
| `phase` | 7 | 44 |
| `mcp-context-server` | 3 | 43 |
| `operator` | 5 | 42 |
| `decision` | 3 | 42 |
| `pr-train` | 7 | 38 |
| `self-healing` | 3 | 37 |
| `diagnosis` | 2 | 34 |
| `schema` | 3 | 28 |
| `workflow-security-compliance` | 2 | 25 |
| `pipeline-pack` | 2 | 24 |
| `heuristic` | 4 | 21 |
| `response` | 4 | 21 |
| `release-executor` | 4 | 20 |
| `semantic-analyzer` | 1 | 20 |
| `repo-dag` | 3 | 20 |
| `tool-registry` | 7 | 19 |
| `spec-parser` | 2 | 19 |
| `logging` | 3 | 18 |
| `bb-platform` | 1 | 18 |
| `control-plane` | 6 | 18 |
| `reporting` | 2 | 16 |
| `bb-test-runner` | 1 | 16 |
| `content-hash` | 1 | 15 |
| `orchestrator` | 2 | 15 |
| `training-capture` | 3 | 15 |
| `fsm` | 2 | 14 |
| `context-pack` | 4 | 13 |
| `connector-jira` | 2 | 13 |
| `metric-registry` | 1 | 13 |
| `workflow-resume` | 1 | 12 |
| `gate-classification` | 3 | 10 |
| `response-chain` | 2 | 10 |
| `connector-pipeline-output` | 2 | 9 |
| `improvement` | 3 | 9 |
| `boundary` | 2 | 9 |
| `connector-http` | 2 | 8 |
| `tool` | 2 | 8 |
| `artifact` | 2 | 7 |
| `adapter-claude-code` | 2 | 7 |
| `task` | 2 | 7 |
| `connector-gitlab` | 1 | 6 |
| `agent-runtime` | 2 | 6 |
| `connector-github` | 1 | 6 |
| `pr-scoring` | 1 | 6 |
| `bb-data-plane-http` | 1 | 5 |
| `policy` | 2 | 5 |
| `algorithms` | 1 | 5 |
| `connector-sarif` | 1 | 3 |
| `connector` | 1 | 3 |
| `bb-config` | 1 | 3 |
| `connector-linter` | 1 | 2 |
| `bb-proc` | 1 | 2 |
| `etl` | 1 | 2 |
| `bb-paths` | 1 | 1 |
| `control-plane-adapter` | 1 | 1 |
| `evaluation` | 1 | 1 |
| `anomaly` | 1 | 1 |
| `cursor-store` | 1 | 1 |

## Top file offenders (≥ 20 violations)

| File | Violations |
|---|---:|
| `components/web-dashboard/src/ai/miniforge/web_dashboard/state/trains.clj` | 118 |
| `components/tui-views/src/ai/miniforge/tui_views/view/project/trees.clj` | 92 |
| `components/tui-views/src/ai/miniforge/tui_views/update.clj` | 85 |
| `components/tui-views/src/ai/miniforge/tui_views/update/events.clj` | 79 |
| `components/workflow/src/ai/miniforge/workflow/fsm.clj` | 78 |
| `components/tui-views/src/ai/miniforge/tui_views/persistence.clj` | 75 |
| `components/config/src/ai/miniforge/config/user.clj` | 68 |
| `components/pr-sync/src/ai/miniforge/pr_sync/core.clj` | 64 |
| `components/tui-views/src/ai/miniforge/tui_views/view/project/helpers.clj` | 59 |
| `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj` | 51 |
| `components/workflow/src/ai/miniforge/workflow/dag_orchestrator.clj` | 46 |
| `components/loop/src/ai/miniforge/loop/inner.clj` | 43 |
| `components/agent/src/ai/miniforge/agent/implementer.clj` | 40 |
| `components/failure-classifier/src/ai/miniforge/failure_classifier/taxonomy.clj` | 40 |
| `components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj` | 37 |
| `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/tasks.clj` | 35 |
| `components/tui-views/src/ai/miniforge/tui_views/subscription.clj` | 35 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj` | 34 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/exceptions_as_data.clj` | 31 |
| `components/failure-classifier/src/ai/miniforge/failure_classifier/classifier.clj` | 29 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/fsm.clj` | 29 |
| `components/tui-views/src/ai/miniforge/tui_views/interface.clj` | 29 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/views.clj` | 29 |
| `components/loop/src/ai/miniforge/loop/outer.clj` | 28 |
| `components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj` | 28 |
| `components/workflow/src/ai/miniforge/workflow/execution.clj` | 27 |
| `components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj` | 26 |
| `components/tui-views/src/ai/miniforge/tui_views/update/command.clj` | 26 |
| `components/operator/src/ai/miniforge/operator/intervention.clj` | 25 |
| `components/tui-views/src/ai/miniforge/tui_views/update/navigation.clj` | 25 |
| `components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj` | 24 |
| `components/policy-pack/src/ai/miniforge/policy_pack/schema.clj` | 24 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/classifier.clj` | 24 |
| `components/workflow/src/ai/miniforge/workflow/checkpoint_store.clj` | 24 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/execute.clj` | 23 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/scan.clj` | 23 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/views/workflows.clj` | 23 |
| `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` | 22 |
| `bases/cli/src/ai/miniforge/cli/tui.clj` | 21 |
| `bases/mcp-context-server/src/ai/miniforge/mcp_context_server/context_cache.clj` | 21 |
| `components/dag-executor/src/ai/miniforge/dag_executor/scheduler.clj` | 21 |
| `components/diagnosis/src/ai/miniforge/diagnosis/correlator.clj` | 21 |
| `components/phase-software-factory/src/ai/miniforge/phase_software_factory/implement.clj` | 21 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_budget.clj` | 21 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_loop.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/update/filter.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/update/mode.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/view/project.clj` | 21 |
| `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/installer.clj` | 20 |
| `components/decision/src/ai/miniforge/decision/spec.clj` | 20 |
| `components/pipeline-pack/src/ai/miniforge/pipeline_pack/loader.clj` | 20 |
| `components/policy-pack/src/ai/miniforge/policy_pack/loader.clj` | 20 |
| `components/semantic-analyzer/src/ai/miniforge/semantic_analyzer/core.clj` | 20 |
