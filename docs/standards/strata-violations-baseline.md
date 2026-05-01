# Stratified-Design Violations — Baseline (2026-05-01, revised)

Audit of `components/**/src` and `bases/**/src` for within-namespace stratified-design violations. Run via `bb
tools/strata_audit.bb`.

## Summary

- **Files audited (with at least one violation):** 394
- **Total violations:** 3861
- **Components affected:** 83

## What counts as a violation?

A violation is an intra-namespace call from a function at Layer A to a function at Layer B where `B >= A`. In stratified
design, every same-namespace call must go strictly downward in layer. Same-layer composition and upward calls are both
rejected.

Layer assignment is determined by the most recent `;-- Layer N` comment marker preceding the function definition. The
audit uses `clj-kondo` analysis to build the call graph.

Var-definitions and var-usages inside `(comment …)` rich-comment blocks are excluded via `clj-kondo --config
{:skip-comments true}`. (Earlier baseline counted REPL experiments as production code.)

## By component (descending)

| Component | Files | Violations |
|---|---:|---:|
| `tui-views` | 34 | 688 |
| `workflow` | 29 | 317 |
| `web-dashboard` | 20 | 297 |
| `cli` | 34 | 228 |
| `policy-pack` | 16 | 179 |
| `pr-lifecycle` | 17 | 173 |
| `agent` | 17 | 156 |
| `compliance-scanner` | 7 | 101 |
| `supervisory-state` | 4 | 80 |
| `loop` | 5 | 78 |
| `lsp-mcp-bridge` | 9 | 78 |
| `phase-deployment` | 12 | 78 |
| `config` | 4 | 77 |
| `pr-sync` | 2 | 72 |
| `failure-classifier` | 2 | 69 |
| `llm` | 4 | 62 |
| `reliability` | 6 | 55 |
| `knowledge` | 7 | 52 |
| `repo-index` | 6 | 47 |
| `phase-software-factory` | 5 | 45 |
| `gate` | 9 | 44 |
| `phase` | 7 | 44 |
| `mcp-context-server` | 3 | 43 |
| `evidence-bundle` | 8 | 42 |
| `operator` | 5 | 41 |
| `tui-engine` | 7 | 39 |
| `self-healing` | 3 | 37 |
| `decision` | 2 | 36 |
| `diagnosis` | 2 | 34 |
| `event-stream` | 7 | 32 |
| `dag-executor` | 7 | 30 |
| `pr-train` | 4 | 26 |
| `workflow-security-compliance` | 2 | 25 |
| `pipeline-pack` | 2 | 24 |
| `response` | 4 | 21 |
| `release-executor` | 4 | 20 |
| `semantic-analyzer` | 1 | 20 |
| `heuristic` | 3 | 20 |
| `tool-registry` | 7 | 19 |
| `spec-parser` | 2 | 19 |
| `schema` | 2 | 18 |
| `bb-platform` | 1 | 18 |
| `bb-test-runner` | 1 | 16 |
| `content-hash` | 1 | 15 |
| `repo-dag` | 2 | 15 |
| `logging` | 3 | 14 |
| `connector-jira` | 2 | 13 |
| `metric-registry` | 1 | 13 |
| `control-plane` | 5 | 13 |
| `training-capture` | 2 | 13 |
| `workflow-resume` | 1 | 12 |
| `reporting` | 1 | 11 |
| `gate-classification` | 3 | 10 |
| `connector-pipeline-output` | 2 | 9 |
| `improvement` | 2 | 8 |
| `connector-http` | 2 | 8 |
| `orchestrator` | 1 | 7 |
| `response-chain` | 1 | 7 |
| `context-pack` | 3 | 6 |
| `connector-gitlab` | 1 | 6 |
| `agent-runtime` | 2 | 6 |
| `adapter-claude-code` | 2 | 6 |
| `connector-github` | 1 | 6 |
| `task` | 2 | 6 |
| `pr-scoring` | 1 | 6 |
| `artifact` | 1 | 5 |
| `bb-data-plane-http` | 1 | 5 |
| `boundary` | 2 | 5 |
| `tool` | 2 | 5 |
| `algorithms` | 1 | 5 |
| `fsm` | 1 | 4 |
| `connector-sarif` | 1 | 3 |
| `connector` | 1 | 3 |
| `bb-config` | 1 | 3 |
| `connector-linter` | 1 | 2 |
| `policy` | 1 | 2 |
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
| `components/tui-views/src/ai/miniforge/tui_views/persistence.clj` | 73 |
| `components/workflow/src/ai/miniforge/workflow/fsm.clj` | 73 |
| `components/config/src/ai/miniforge/config/user.clj` | 68 |
| `components/pr-sync/src/ai/miniforge/pr_sync/core.clj` | 64 |
| `components/tui-views/src/ai/miniforge/tui_views/view/project/helpers.clj` | 59 |
| `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj` | 51 |
| `components/workflow/src/ai/miniforge/workflow/dag_orchestrator.clj` | 46 |
| `components/failure-classifier/src/ai/miniforge/failure_classifier/taxonomy.clj` | 40 |
| `components/agent/src/ai/miniforge/agent/implementer.clj` | 39 |
| `components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj` | 37 |
| `components/loop/src/ai/miniforge/loop/inner.clj` | 36 |
| `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/tasks.clj` | 35 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj` | 34 |
| `components/tui-views/src/ai/miniforge/tui_views/subscription.clj` | 32 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/exceptions_as_data.clj` | 30 |
| `components/failure-classifier/src/ai/miniforge/failure_classifier/classifier.clj` | 29 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/fsm.clj` | 29 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/views.clj` | 29 |
| `components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj` | 28 |
| `components/tui-views/src/ai/miniforge/tui_views/interface.clj` | 27 |
| `components/workflow/src/ai/miniforge/workflow/execution.clj` | 27 |
| `components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj` | 26 |
| `components/tui-views/src/ai/miniforge/tui_views/update/command.clj` | 26 |
| `components/operator/src/ai/miniforge/operator/intervention.clj` | 25 |
| `components/tui-views/src/ai/miniforge/tui_views/update/navigation.clj` | 25 |
| `components/policy-pack/src/ai/miniforge/policy_pack/schema.clj` | 24 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/classifier.clj` | 24 |
| `components/workflow/src/ai/miniforge/workflow/checkpoint_store.clj` | 24 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/execute.clj` | 23 |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/views/workflows.clj` | 23 |
| `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` | 22 |
| `components/compliance-scanner/src/ai/miniforge/compliance_scanner/scan.clj` | 22 |
| `components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj` | 22 |
| `bases/cli/src/ai/miniforge/cli/tui.clj` | 21 |
| `bases/mcp-context-server/src/ai/miniforge/mcp_context_server/context_cache.clj` | 21 |
| `components/diagnosis/src/ai/miniforge/diagnosis/correlator.clj` | 21 |
| `components/phase-software-factory/src/ai/miniforge/phase_software_factory/implement.clj` | 21 |
| `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_loop.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/update/filter.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/update/mode.clj` | 21 |
| `components/tui-views/src/ai/miniforge/tui_views/view/project.clj` | 21 |
| `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/installer.clj` | 20 |
| `components/decision/src/ai/miniforge/decision/spec.clj` | 20 |
| `components/pipeline-pack/src/ai/miniforge/pipeline_pack/loader.clj` | 20 |
| `components/semantic-analyzer/src/ai/miniforge/semantic_analyzer/core.clj` | 20 |
