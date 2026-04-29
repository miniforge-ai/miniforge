# Exception Cleanup Inventory

**Generated:** 2026-04-23
**Branch:** `docs/exception-cleanup-inventory`
**Method:** Ripgrep over `components/` and `bases/` for
  `throw|ex-info|IllegalArgumentException.|RuntimeException.|IllegalStateException.|Exception.|Throwable.`, then manual
  categorization with two-line context.

## Scope

This inventory covers production source files only — `components/**/src/**/*.clj` and `bases/**/src/**/*.clj`. Test
files (`**/test/**/*.clj`) contribute another **479** hits across **111** component test files plus a handful in bases,
but those are predominantly `is (thrown? ...)` matchers that exist *because* the production code throws. They are
derivative work: every `:cleanup-needed` site in production code that becomes anomaly-returning will require updating
one or more of those tests in the same PR. They are not separately schedulable cleanup units.

Docstring mentions of "throws" / "throw ex-info" (e.g. `"Returns x or throws"`) are counted toward their enclosing
function's hit, not as standalone occurrences.

The `response/throw-anomaly!` family is the canonical anomaly-as-thrown-data primitive (slingshot `throw+` of an anomaly
map). It is the *target* of the migration, not a problem. Sites already using it are categorized by whether their
location is appropriate (typically `:fatal-only` or `:boundary`) — they are NOT `:cleanup-needed`, but they may be
flagged `:ambiguous` if the throw is happening too far inside a code path where a return value would be cleaner.

## Summary

| Category | Count |
|----------|-------|
| `:boundary`        | 41  |
| `:fatal-only`      | 96  |
| `:cleanup-needed`  | 158 |
| `:ambiguous`       | 37  |
| **Total (production src)** | **332 components + ~28 bases = ~360** |

Test-file hits (out of scope for independent PRs, in-scope for the PRs that change related production code): **~479** in
components + **~14** in bases.

### Production source totals by base

| Base | Total hits | Source files |
|------|-----------:|-------------:|
| `bases/cli`               | 18 | 7 |
| `bases/mcp-context-server`|  7 | 2 |
| `bases/lsp-mcp-bridge`    |  2 | 2 |
| `bases/etl`               |  1 | 1 |

### Production source totals by component (components with ≥ 5 hits, ranked)

| Component | Total hits | `:cleanup-needed` | PR sizing |
|-----------|-----------:|------------------:|-----------|
| `repo-dag`              | 17 | 12 | single PR (focused on `core.clj`) |
| `response`              | 22 |  0 | none — defines throw-anomaly!; documentation hits only |
| `spec-parser`           | 10 |  6 | single PR |
| `connector-github`      |  9 |  6 | single PR |
| `connector-jira`        |  9 |  5 | single PR |
| `connector-gitlab`      |  9 |  5 | single PR |
| `task`                  |  8 |  5 | single PR |
| `connector-excel`       |  8 |  5 | single PR |
| `agent` (all namespaces)| 18 |  6 | single PR (`role_config`, `prompts`, `planner`, `reviewer`, `meta_protocol`, `messaging`, `core`, `file_artifacts`, `artifact_session`) |
| `workflow` (runner+)    | 26 |  9 | **split into 2 PRs**: (a) `runner.clj`+`state.clj`+`monitoring.clj`+`runner_environment.clj`+`runner_cleanup.clj`+`observe_phase.clj`+`agent_factory.clj`+`supervision.clj`; (b) `loader.clj`+`chain_loader.clj`+`registry.clj`+`schemas.clj`+`dag_orchestrator.clj` |
| `connector-http`        |  7 |  3 | single PR |
| `pr-lifecycle`          | 11 |  6 | single PR |
| `connector-edgar`       |  7 |  4 | single PR |
| `connector-file`        |  7 |  3 | single PR |
| `event-stream`          | 11 |  6 | single PR |
| `dag-executor`          | 11 |  3 | single PR |
| `tool-registry`         |  4 |  3 | single PR |
| `policy-pack`           |  7 |  4 | single PR |
| `bb-data-plane-http`    | 10 |  4 | single PR |
| `operator`              |  6 |  5 | single PR |
| `task-executor`         |  7 |  4 | single PR |
| `config`                |  6 |  3 | single PR |
| `loop`                  |  4 |  2 | single PR |
| `connector-sarif`       |  4 |  2 | single PR |
| `phase-software-factory`|  5 |  2 | single PR |
| `bb-r2`                 |  4 |  2 | single PR |
| `llm`                   |  3 |  1 | single PR |
| `connector-pipeline-output` |  5 | 1 | single PR |
| `bb-proc`               |  5 |  2 | single PR |
| Many smaller components |  varies |  1–2 each | bundle into one or two PRs (see "ordering") |

No single component has > 20 `:cleanup-needed` hits. Only `workflow` is split for PR sizing because of the high coupling
and risk surface (workflow runner is the orchestration entry point).

---

## Per-component breakdown

### components/agent

- **Total hits:** 18 (across 8 namespaces)
- **Cleanup-needed:** 6
- **PR-sizing recommendation:** single PR

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/agent/protocols/impl/messaging.clj` | 244 | `response/throw-anomaly!` | :fatal-only | Validation guard inside `validate-message`. Already using anomaly primitive. Acceptable. |
| `src/ai/miniforge/agent/role_config.clj` | 46 | `throw ex-info` | :fatal-only | `lookup-or-throw` helper for unknown agent role keyword — programmer error, exhaustive map lookup. |
| `src/ai/miniforge/agent/role_config.clj` | 42-43, 63, 66, 87, 89 | docstrings/refs | :fatal-only | Docstring mentions; counted with above. |
| `src/ai/miniforge/agent/prompts.clj` | 47 | `response/throw-anomaly!` | :ambiguous | `:anomalies/not-found` for missing `:prompt/system` in EDN — acceptable boundary at startup, but caller could prefer return-anomaly to short-circuit recovery. |
| `src/ai/miniforge/agent/prompts.clj` | 52 | `response/throw-anomaly!` | :ambiguous | Same — missing prompt resource. Boundary-ish (startup). |
| `src/ai/miniforge/agent/prompts.clj` | 70 | `response/throw-anomaly!` | :ambiguous | Same — missing prompt resource. |
| `src/ai/miniforge/agent/planner.clj` | 467 | `response/throw-anomaly!` | :cleanup-needed | Plan EDN parse failure inside `invoke-fn` — should return anomaly to caller (workflow runner can decide). |
| `src/ai/miniforge/agent/planner.clj` | 518 | `response/throw-anomaly!` | :cleanup-needed | "No LLM backend provided" — caller should receive a typed result. |
| `src/ai/miniforge/agent/planner.clj` | 169 | docstring | n/a | Counted with line 467. |
| `src/ai/miniforge/agent/reviewer.clj` | 100 | docstring | n/a | "Create error feedback when gate throws" — describes catch site, no throw. |
| `src/ai/miniforge/agent/meta_protocol.clj` | 133 | `response/throw-anomaly!` | :cleanup-needed | Constructor validation — should return anomaly, not throw. |
| `src/ai/miniforge/agent/file_artifacts.clj` | 164 | `response/throw-anomaly!` | :cleanup-needed | Snapshot-working-directory failure — should return anomaly. |
| `src/ai/miniforge/agent/artifact_session.clj` | 477 | docstring | n/a | "never thrown" — documentation. |
| `src/ai/miniforge/agent/core.clj` | 36 | `:require [throw+]` | :boundary | slingshot `throw+` import for the agent invoke boundary. Acceptable. |
| `src/ai/miniforge/agent/core.clj` | 445, 490 | `:throwable &throw-context` | :boundary | slingshot try+ catch — extracts throwable, then re-classifies. Boundary handling. |

### components/agent-runtime

- **Total hits:** 1 (docstring) | **Cleanup-needed:** 0 | **No PR needed**

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/agent_runtime/interface.clj` | 60 | `ex-info` (in docstring example) | n/a | Documentation only. |

### components/artifact

- **Total hits:** 2 | **Cleanup-needed:** 2 | **PR-sizing:** single PR

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/artifact/protocols/impl/transit_store.clj` | 231 | `throw ex-info "Parent artifact not found"` | :cleanup-needed | Lookup failure — should return anomaly. |
| `src/ai/miniforge/artifact/protocols/impl/transit_store.clj` | 233 | `throw ex-info "Child artifact not found"` | :cleanup-needed | Same. |

### components/bb-adapter-thesium-risk

- **Total hits:** 1 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_adapter_thesium_risk/core.clj` | 93 | `throw ex-info "FRED_API_KEY is required"` | :fatal-only | Startup config check; programmer error. |

### components/bb-data-plane-http

- **Total hits:** 10 | **Cleanup-needed:** 4 | **PR-sizing:** single PR

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_data_plane_http/interface.clj` | 69, 89, 96 | docstrings | n/a | "Throws ex-info on..." documentation. |
| `src/ai/miniforge/bb_data_plane_http/core.clj` | 85, 108, 128 | `:throw false` opt | n/a | babashka.http opt key — false positive. |
| `src/ai/miniforge/bb_data_plane_http/core.clj` | 95 | `throw ex-info "Data plane exited"` | :cleanup-needed | Wait-ready failure — caller likely wants typed result. |
| `src/ai/miniforge/bb_data_plane_http/core.clj` | 96 | `throw ex-info "Data plane did not become ready"` | :cleanup-needed | Same. |
| `src/ai/miniforge/bb_data_plane_http/core.clj` | 120 | `throw ex-info "GET … failed"` | :cleanup-needed | HTTP failure — should return anomaly map. |
| `src/ai/miniforge/bb_data_plane_http/core.clj` | 131 | `throw ex-info "POST … failed"` | :cleanup-needed | Same. |

### components/bb-generate-icon

- **Total hits:** 1 | **Cleanup-needed:** 1 | single PR (or bundle)

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_generate_icon/core.clj` | 91 | `throw ex-info "No source icon …"` | :cleanup-needed | Should return anomaly so caller can fall back. |

### components/bb-paths

- **Total hits:** 2 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_paths/core.clj` | 48 | docstring | n/a | "Throws ex-info if no bb.edn …" |
| `src/ai/miniforge/bb_paths/core.clj` | 51 | `throw ex-info "Could not locate bb.edn"` | :cleanup-needed | Boundary helper but called widely — better to return nil or anomaly so callers can recover. |

### components/bb-proc

- **Total hits:** 5 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_proc/interface.clj` | 27, 40 | docstrings | n/a | |
| `src/ai/miniforge/bb_proc/core.clj` | 44, 63 | docstrings | n/a | |
| `src/ai/miniforge/bb_proc/core.clj` | 50 | `throw ex-info "Command failed"` | :cleanup-needed | `run!` non-zero exit — caller should choose. Note `sh` already returns data; consolidate around that. |

### components/bb-r2

- **Total hits:** 4 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/bb_r2/interface.clj` | 30 | docstring | n/a | |
| `src/ai/miniforge/bb_r2/core.clj` | 56 | `throw ex-info ":worker-dir required"` | :fatal-only | Programmer-error guard. |
| `src/ai/miniforge/bb_r2/core.clj` | 58 | `throw ex-info ":bucket required"` | :fatal-only | Programmer-error guard. |
| `src/ai/miniforge/bb_r2/core.clj` | 68 | `throw ex-info "wrangler r2 get failed"` | :cleanup-needed | External call failure — caller should decide. |

### components/config

- **Total hits:** 6 | **Cleanup-needed:** 3 | single PR

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/config/user.clj` | 119 | docstring "never throws" | n/a | |
| `src/ai/miniforge/config/governance.clj` | 123 | docstring | n/a | |
| `src/ai/miniforge/config/governance.clj` | 131 | `throw ex-info "Governance config integrity"` | :fatal-only | :knowledge-safety integrity check — security-critical, fail-fast is appropriate. |
| `src/ai/miniforge/config/governance.clj` | 177 | `throw ex-info "Only :trusted packs"` | :cleanup-needed | Trust-level check — should return validation anomaly. |
| `src/ai/miniforge/config/governance.clj` | 189 | `throw ex-info "Knowledge-safety pattern category … would shrink"` | :cleanup-needed | Pack validation — return anomaly. |
| `src/ai/miniforge/config/governance.clj` | 224 | `throw ex-info "Governance config not found"` | :cleanup-needed | Lookup failure — return anomaly. |

### components/connector

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector/retry.clj` | 24 | docstring | n/a | |
| `src/ai/miniforge/connector/retry.clj` | 28 | `throw ex-info "Unknown connector retry policy"` | :fatal-only | Namespace-load-time guard; misconfigured key fails loud at boot. |

### components/connector-edgar

- **Total hits:** 7 | **Cleanup-needed:** 4

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_edgar/impl.clj` | 43 | `:throw false` opt | n/a | http opt. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 196 | `throw ex-info :edgar/form-type-required` | :fatal-only | Connect-time config validation. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 197 | `throw ex-info :edgar/user-agent-required` | :fatal-only | Same. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 198 | `throw ex-info :edgar/aggregation-required` | :fatal-only | Same. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 214 | `throw ex-info :edgar/handle-not-found` | :cleanup-needed | Handle lookup; connector protocol could return anomaly. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 225 | `throw ex-info :edgar/aggregation-unknown` | :cleanup-needed | Default branch in case — runtime-reachable, return anomaly. |
| `src/ai/miniforge/connector_edgar/impl.clj` | 228 | `throw ex-info :edgar/handle-not-found` | :cleanup-needed | Same as 214. |

### components/connector-excel

- **Total hits:** 8 | **Cleanup-needed:** 5

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_excel/impl.clj` | 70 | `throw ex-info :excel/sheet-not-found` | :cleanup-needed | Runtime data error — return anomaly. |
| `src/ai/miniforge/connector_excel/impl.clj` | 107 | `:throw false` opt | n/a | |
| `src/ai/miniforge/connector_excel/impl.clj` | 110 | `throw ex-info :excel/download-failed` | :cleanup-needed | HTTP error — return anomaly. |
| `src/ai/miniforge/connector_excel/impl.clj` | 127 | `throw ex-info :excel/url-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_excel/impl.clj` | 128 | `throw ex-info :excel/sheet-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_excel/impl.clj` | 129 | `throw ex-info :excel/columns-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_excel/impl.clj` | 152 | `throw ex-info :excel/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_excel/impl.clj` | 166 | `throw ex-info :excel/handle-not-found` | :cleanup-needed | Handle lookup. |

### components/connector-file

- **Total hits:** 7 | **Cleanup-needed:** 3

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_file/impl.clj` | 28 | `throw ex-info :file/path-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_file/impl.clj` | 29 | `throw ex-info :file/format-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_file/impl.clj` | 31 | `throw ex-info :file/format-unsupported` | :fatal-only | Exhaustive set check. |
| `src/ai/miniforge/connector_file/impl.clj` | 52 | `throw ex-info :file/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_file/impl.clj` | 60 | `throw ex-info :file/not-found` | :cleanup-needed | Runtime IO. |
| `src/ai/miniforge/connector_file/impl.clj` | 71 | `throw ex-info :file/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_file/impl.clj` | 87 | `throw ex-info :file/handle-not-found` | :ambiguous | Same — but happens after a write side-effect; ordering-sensitive cleanup. |

### components/connector-github

- **Total hits:** 9 | **Cleanup-needed:** 6

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_github/resources.clj` | 14 | `throw ex-info "resources not found"` | :fatal-only | Boot-time resource load. |
| `src/ai/miniforge/connector_github/impl.clj` | 108 | `http/throw-on-failure!` (call) | :ambiguous | Internal HTTP failure path; see `connector-http/request.clj:79` — wrapper throws. |
| `src/ai/miniforge/connector_github/impl.clj` | 163 | docstring | n/a | |
| `src/ai/miniforge/connector_github/impl.clj` | 166 | `throw ex-info :github/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_github/impl.clj` | 170 | docstring | n/a | |
| `src/ai/miniforge/connector_github/impl.clj` | 173 | `throw ex-info :github/resource-unknown` | :cleanup-needed | Resource lookup at runtime. |
| `src/ai/miniforge/connector_github/impl.clj` | 182 | docstring | n/a | |
| `src/ai/miniforge/connector_github/impl.clj` | 189 | `throw ex-info :github/config-invalid` | :cleanup-needed | Config validation — return anomaly. |
| `src/ai/miniforge/connector_github/impl.clj` | 192 | `throw ex-info :github/owner-or-org-required` | :cleanup-needed | Config validation. |
| `src/ai/miniforge/connector_github/impl.clj` | 196 | `throw ex-info :github/auth-invalid` | :cleanup-needed | Auth failure — return typed result. |

### components/connector-gitlab

- **Total hits:** 9 | **Cleanup-needed:** 5 (mirror of connector-jira/connector-github)

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_gitlab/schema.clj` | 30, 33 | docstring + `throw ex-info "validation failed"` | :cleanup-needed | `validate!` helper — change to anomaly-returning variant. |
| `src/ai/miniforge/connector_gitlab/resources.clj` | 16 | `throw ex-info "resources not found"` | :fatal-only | Boot-time. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 24, 27 | doc + `throw ex-info :gitlab/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 71, 76 | doc + `throw ex-info :gitlab/auth-invalid` | :cleanup-needed | Auth validation. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 120 | `http/throw-on-failure!` | :ambiguous | Internal HTTP layer. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 140, 143 | doc + `throw ex-info :gitlab/resource-unknown` | :cleanup-needed | Resource lookup. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 200 | `throw ex-info :gitlab/project-required` | :fatal-only | Connect-time config. |
| `src/ai/miniforge/connector_gitlab/impl.clj` | 233 | `(throw e)` rethrow inside catch | :boundary | Re-throw of permanent error after type check; appropriate. |

### components/connector-http

- **Total hits:** 7 | **Cleanup-needed:** 3

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_http/interface.clj` | 36 | re-export `throw-on-failure!` | n/a | API surface re-export of below. |
| `src/ai/miniforge/connector_http/impl.clj` | 51 | `:throw false` opt | n/a | http opt. |
| `src/ai/miniforge/connector_http/impl.clj` | 112 | `throw ex-info :http/base-url-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_http/impl.clj` | 113 | `throw ex-info :http/endpoint-required` | :fatal-only | Config validation. |
| `src/ai/miniforge/connector_http/impl.clj` | 135 | `throw ex-info :http/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_http/impl.clj` | 148 | `throw ex-info` (HTTP failure) | :cleanup-needed | Should return anomaly. |
| `src/ai/miniforge/connector_http/impl.clj` | 193 | `throw ex-info :http/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_http/request.clj` | 68 | `:throw false` opt | n/a | |
| `src/ai/miniforge/connector_http/request.clj` | 75-79 | `throw-on-failure!` definition | :ambiguous | Helper that throws on failure result. Keep this one too — but mark all callers as `:cleanup-needed`/`:ambiguous` as we replace it with `failure-or-result` variant. |

### components/connector-jira

- **Total hits:** 9 | **Cleanup-needed:** 5

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_jira/schema.clj` | 92, 95 | doc + `throw ex-info "validation failed"` | :cleanup-needed | `validate!` — anomaly variant. |
| `src/ai/miniforge/connector_jira/resources.clj` | 16 | `throw ex-info "resources not found"` | :fatal-only | Boot-time. |
| `src/ai/miniforge/connector_jira/impl.clj` | 24, 27 | doc + `throw ex-info :jira/handle-not-found` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_jira/impl.clj` | 51, 56 | doc + `throw ex-info :jira/auth-invalid` | :cleanup-needed | Auth validation. |
| `src/ai/miniforge/connector_jira/impl.clj` | 73, 76 | doc + `throw ex-info :jira/resource-unknown` | :cleanup-needed | Resource lookup. |
| `src/ai/miniforge/connector_jira/impl.clj` | 93 | `http/throw-on-failure!` | :ambiguous | Internal HTTP. |
| `src/ai/miniforge/connector_jira/impl.clj` | 109 | `throw ex-info :jira/site-required` | :fatal-only | Connect-time config. |

### components/connector-pipeline-output

- **Total hits:** 5 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_pipeline_output/format.clj` | 32 | `throw ex-info "Unsupported format"` | :fatal-only | defmethod default — exhaustive case. |
| `src/ai/miniforge/connector_pipeline_output/schema.clj` | 57, 60 | doc + `throw ex-info "validation failed"` | :cleanup-needed | `validate!` helper. |
| `src/ai/miniforge/connector_pipeline_output/impl.clj` | 20, 23 | doc + `throw ex-info :output/handle-not-found` | :cleanup-needed | Same handle-lookup pattern. (counts as one cleanup, two hits) |

### components/connector-retry

- **Total hits:** 1 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_retry/backoff.clj` | 32 | `throw ex-info "Unknown retry strategy"` | :fatal-only | Exhaustive case, configuration error. |

### components/connector-sarif

- **Total hits:** 4 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/connector_sarif/format.clj` | 169 | `throw ex-info "Unsupported format"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/connector_sarif/impl.clj` | 33, 36 | doc + `throw ex-info "Unknown handle"` | :cleanup-needed | Handle lookup. |
| `src/ai/miniforge/connector_sarif/impl.clj` | 46 | `throw ex-info "Invalid SARIF config"` | :cleanup-needed | Config validation — return anomaly. |

### components/control-plane

- **Total hits:** 4 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/control_plane/registry.clj` | 236 | `throw ex-info :registry/agent-not-found` | :cleanup-needed | Lookup failure — return anomaly. |
| `src/ai/miniforge/control_plane/state_machine.clj` | 55 | `throw ex-info :state-machine/profile-not-found` | :fatal-only | Boot-time resource. |
| `src/ai/miniforge/control_plane/state_machine.clj` | 99 | docstring | n/a | |
| `src/ai/miniforge/control_plane/state_machine.clj` | 109 | `throw ex-info :state-machine/invalid-transition` | :cleanup-needed | Used by `registry/transition!` — should return anomaly. |

### components/dag-executor

- **Total hits:** 11 | **Cleanup-needed:** 3

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/dag_executor/protocols/impl/docker.clj` | 481-486 | doc + `:throw-on-error?` option | n/a | Existing flag for caller-controlled throwing. |
| `src/ai/miniforge/dag_executor/protocols/impl/docker.clj` | 489 | conditional throw | :ambiguous | Behavior controlled by caller flag — already a knob. May want to remove the throwing branch entirely. |
| `src/ai/miniforge/dag_executor/protocols/impl/docker.clj` | 495 | `response/throw-anomaly! :anomalies/fault` | :ambiguous | Same path as above — the throwing branch. |
| `src/ai/miniforge/dag_executor/protocols/impl/docker.clj` | 554 | `:throw-on-error? true` | n/a | Call-site of above. |
| `src/ai/miniforge/dag_executor/interface.clj` | 61 | docstring | n/a | `unwrap` doc. |
| `src/ai/miniforge/dag_executor/execution_plan.clj` | 114 | docstring | n/a | |
| `src/ai/miniforge/dag_executor/execution_plan.clj` | 119 | `response/throw-anomaly! :anomalies/incorrect` | :cleanup-needed | `validate-execution-plan!` — should return validation anomaly map; throwing here forces try/catch in callers. |
| `src/ai/miniforge/dag_executor/execution_plan.clj` | 165 | docstring | n/a | |

### components/dag-primitives

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/dag_primitives/result.clj` | 44 | docstring | n/a | |
| `src/ai/miniforge/dag_primitives/result.clj` | 48 | `throw ex-info "Unwrap called on error"` | :fatal-only | `unwrap` programmer-error — caller should have checked `ok?` first. |

### components/decision

- **Total hits:** 2 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/decision/spec.clj` | 204 | docstring | n/a | |
| `src/ai/miniforge/decision/spec.clj` | 208 | `throw ex-info "Decision schema validation failed"` | :cleanup-needed | `validate` — should return anomaly variant. |

### components/event-stream

- **Total hits:** 11 | **Cleanup-needed:** 6

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/event_stream/listeners.clj` | 109 | `throw ex-info "Invalid capability level"` | :cleanup-needed | Anomaly is wrapped inside ex-data — should return the anomaly directly. |
| `src/ai/miniforge/event_stream/listeners.clj` | 187 | docstring | n/a | |
| `src/ai/miniforge/event_stream/listeners.clj` | 191 | `throw ex-info "Listener not found"` | :cleanup-needed | Same — anomaly already constructed. |
| `src/ai/miniforge/event_stream/listeners.clj` | 197 | `throw ex-info "Insufficient capability"` | :cleanup-needed | Same. |
| `src/ai/miniforge/event_stream/listeners.clj` | 225 | docstring | n/a | |
| `src/ai/miniforge/event_stream/listeners.clj` | 229 | `throw ex-info "Listener not found"` | :cleanup-needed | Same. |
| `src/ai/miniforge/event_stream/listeners.clj` | 235 | `throw ex-info "Insufficient capability"` | :cleanup-needed | Same. |
| `src/ai/miniforge/event_stream/core.clj` | 805 | docstring | n/a | event-shape doc. |
| `src/ai/miniforge/event_stream/sinks.clj` | 242 | `throw ex-info "Fleet sink requires :url"` | :fatal-only | Boot-time validation. |
| `src/ai/miniforge/event_stream/sinks.clj` | 324 | `throw ex-info "Unknown sink type"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/event_stream/sinks.clj` | 331 | `throw ex-info "Invalid sink configuration"` | :cleanup-needed | :else branch with arbitrary input — return anomaly. |

### components/evidence-bundle

- **Total hits:** 4 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/evidence_bundle/extraction.clj` | 126 | docstring | n/a | |
| `src/ai/miniforge/evidence_bundle/protocols/records/evidence_bundle.clj` | 85 | `throw ex-info "artifact-store is required"` | :fatal-only | Constructor guard. |
| `src/ai/miniforge/evidence_bundle/workflow_integration.clj` | 58 | `throw ex-info "evidence-manager required"` | :fatal-only | Constructor guard. |
| `src/ai/miniforge/evidence_bundle/workflow_integration.clj` | 60 | `throw ex-info "artifact-store required"` | :fatal-only | Constructor guard. |

### components/failure-classifier

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/failure_classifier/interface.clj` | 76 | docstring example | n/a | |
| `src/ai/miniforge/failure_classifier/classifier.clj` | 144 | docstring example | n/a | |

### components/fsm

- **Total hits:** 1 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/fsm/core.clj` | 147 | `(throw e)` rethrow | :boundary | Inside try/catch around `automat` — re-throws non-handled cases. Appropriate. |

### components/gate

- **Total hits:** 1 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/gate/interface.clj` | 214 | `response/throw-anomaly! :anomalies.gate/validation-failed` | :cleanup-needed | Gate result is data; throwing here defeats the gate's whole purpose. Return failure result. |

### components/gate-classification

- **Total hits:** 1 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/gate_classification/interface.clj` | 43 | `throw ex-info :schema/invalid-config` | :cleanup-needed | Config validation — return anomaly. |

### components/heuristic

- **Total hits:** 1 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/heuristic/store.clj` | 148 | `throw ex-info "Unknown store type"` | :fatal-only | Constructor exhaustive case. |

### components/knowledge

- **Total hits:** 2 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/knowledge/store.clj` | 176 | `(throw e)` rethrow inside finally-cleanup | :boundary | Re-throw after temp-file cleanup. Appropriate. |
| `src/ai/miniforge/knowledge/trust.clj` | 280 | `throw ex-info "Invalid pack graph"` | :cleanup-needed | Cross-trust validation — return anomaly. |

### components/llm

- **Total hits:** 3 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/llm/protocols/records/llm_client.clj` | 57 | `response/throw-anomaly! :anomalies/incorrect` | :fatal-only | Constructor with unknown backend; programmer error. |
| `src/ai/miniforge/llm/protocols/impl/llm_client.clj` | 265 | docstring | n/a | |
| `src/ai/miniforge/llm/model_registry.clj` | 45 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Missing catalog resource — caller could prefer typed result for fallbacks. |

### components/logging

- **Total hits:** 3 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/logging/sinks.clj` | 151 | `throw ex-info "Fleet sink requires :url"` | :fatal-only | Boot-time. |
| `src/ai/miniforge/logging/sinks.clj` | 226 | `throw ex-info "Unknown sink type"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/logging/sinks.clj` | 233 | `throw ex-info "Invalid sink configuration"` | :cleanup-needed | :else branch on arbitrary input — return anomaly. |

### components/loop

- **Total hits:** 4 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/loop/inner.clj` | 133 | docstring | n/a | |
| `src/ai/miniforge/loop/inner.clj` | 138 | `throw ex-info invalid-transition-message` | :cleanup-needed | FSM transition; better to return validation result. |
| `src/ai/miniforge/loop/inner.clj` | 604 | `throw ex-info :inner/unknown-state` | :fatal-only | Exhaustive case (shouldn't happen comment). |
| `src/ai/miniforge/loop/inner.clj` | 646 | docstring/comment | n/a | |

### components/operator

- **Total hits:** 6 | **Cleanup-needed:** 5

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/operator/intervention.clj` | 41 | `throw ex-info "Missing operator config"` | :fatal-only | Boot-time. |
| `src/ai/miniforge/operator/intervention.clj` | 160 | `throw ex-info :intervention/unknown-type` | :cleanup-needed | Validation — return anomaly. |
| `src/ai/miniforge/operator/intervention.clj` | 164 | `throw ex-info :intervention/target-type-required` | :cleanup-needed | Same. |
| `src/ai/miniforge/operator/intervention.clj` | 168 | `throw ex-info :intervention/unknown-target-type` | :cleanup-needed | Same. |
| `src/ai/miniforge/operator/intervention.clj` | 173 | `throw ex-info :intervention/target-id-required` | :cleanup-needed | Same. |
| `src/ai/miniforge/operator/intervention.clj` | 177 | `throw ex-info :intervention/requester-required` | :cleanup-needed | Same. |

### components/phase

- **Total hits:** 2 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/phase/registry.clj` | 81 | `throw ex-info "Unknown phase type"` | :cleanup-needed | defmethod default — but workflow runner could surface this as anomaly. |
| `src/ai/miniforge/phase/agent_behavior.clj` | 182 | docstring "never throws" | n/a | |

### components/phase-software-factory

- **Total hits:** 5 | **Cleanup-needed:** 2

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/phase_software_factory/implement.clj` | 105 | `throw ex-info :implement/no-worktree` | :ambiguous | Phase pre-flight — could be a phase-result failure rather than throw. |
| `src/ai/miniforge/phase_software_factory/verify.clj` | 159 | `throw ex-info :verify/no-environment` | :ambiguous | Same — pre-flight phase guard. |
| `src/ai/miniforge/phase_software_factory/release.clj` | 84 | `throw ex-info :release/no-implement-artifact` | :cleanup-needed | Phase result should be a failure response. |
| `src/ai/miniforge/phase_software_factory/release.clj` | 198 | `throw ex-info :release/zero-files` | :cleanup-needed | Same. |
| `src/ai/miniforge/phase_software_factory/release.clj` | 327 | `(ex-info … as failure body)` | :boundary | Wrapped in `response/failure` — already data. Acceptable. |
| `src/ai/miniforge/phase_software_factory/pr_monitor.clj` | 98 | `(ex-info … as failure body)` | :boundary | Same pattern. |

### components/pipeline-config

- **Total hits:** 1 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/pipeline_config/connector_registry.clj` | 43 | `throw ex-info :registry/connector-not-found` | :cleanup-needed | Reduce step throws on unknown type — better to short-circuit with anomaly. |

### components/policy-pack

- **Total hits:** 7 | **Cleanup-needed:** 4

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/policy_pack/registry.clj` | 70 | docstring | n/a | |
| `src/ai/miniforge/policy_pack/registry.clj` | 201 | `throw ex-info "Invalid pack schema"` | :cleanup-needed | Validation — return anomaly. |
| `src/ai/miniforge/policy_pack/registry.clj` | 259 | `throw ex-info "File/URL import not implemented"` | :fatal-only | Programmer-error placeholder — may want anomaly with `:anomalies/unsupported`. |
| `src/ai/miniforge/policy_pack/registry.clj` | 267 | `throw ex-info "JSON export not implemented"` | :cleanup-needed | Should be anomaly. |
| `src/ai/miniforge/policy_pack/registry.clj` | 268 | `throw ex-info "Directory export not implemented"` | :cleanup-needed | Same. |
| `src/ai/miniforge/policy_pack/registry.clj` | 269 | `throw ex-info "Unknown export format"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/policy_pack/registry.clj` | 270 | `throw ex-info "Pack not found"` | :cleanup-needed | Lookup — return anomaly. |

### components/pr-lifecycle

- **Total hits:** 11 | **Cleanup-needed:** 6

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/pr_lifecycle/fsm.clj` | 56 | `throw ex-info :config/missing-resource` | :fatal-only | Boot-time. |
| `src/ai/miniforge/pr_lifecycle/controller_config.clj` | 54 | same | :fatal-only | Boot-time. |
| `src/ai/miniforge/pr_lifecycle/monitor_config.clj` | 45 | same | :fatal-only | Boot-time. |
| `src/ai/miniforge/pr_lifecycle/responder.clj` | 196 | docstring | n/a | |
| `src/ai/miniforge/pr_lifecycle/responder.clj` | 200 | `throw ex-info "Failed to fetch PR comments"` | :cleanup-needed | Network error — return anomaly. |
| `src/ai/miniforge/pr_lifecycle/responder.clj` | 243 | `throw ex-info "Could not parse PR number"` | :cleanup-needed | Parse failure. |
| `src/ai/miniforge/pr_lifecycle/controller.clj` | 99-101 | `(ex-info …)` constructor for invalid-transition | :ambiguous | Encapsulated, but used at line 127 with throw — see below. |
| `src/ai/miniforge/pr_lifecycle/controller.clj` | 127 | `(throw (invalid-transition-ex …))` | :cleanup-needed | FSM transition — failed transition is data-y. |
| `src/ai/miniforge/pr_lifecycle/controller.clj` | 381 | `throw ex-info :controller/max-fix-iterations-exceeded` | :cleanup-needed | Limit exceeded — should be a controller status update + anomaly. |
| `src/ai/miniforge/pr_lifecycle/controller.clj` | 413 | same | :cleanup-needed | Same. |
| `src/ai/miniforge/pr_lifecycle/controller.clj` | 508 | `throw ex-info :controller/pr-creation-failed` | :cleanup-needed | External call failure — return anomaly. |

### components/pr-scoring

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/pr_scoring/core.clj` | 82 | `throw ex-info "missing trigger config"` | :fatal-only | Boot-time resource. |
| `src/ai/miniforge/pr_scoring/core.clj` | 117 | docstring | n/a | "scorer-fn that throws is suppressed" — describes catch behaviour. |

### components/repo-dag

- **Total hits:** 17 | **Cleanup-needed:** 12 | **PR-sizing:** single PR (concentrated in `core.clj`)

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/repo_dag/core.clj` | 36 | docstring | n/a | |
| `src/ai/miniforge/repo_dag/core.clj` | 40 | `throw ex-info "Schema validation failed"` | :cleanup-needed | `validate-schema` — anomaly variant. |
| `src/ai/miniforge/repo_dag/core.clj` | 310 | `throw ex-info "Repo already exists"` | :cleanup-needed | Mutation API — return anomaly. |
| `src/ai/miniforge/repo_dag/core.clj` | 315 | `throw ex-info "DAG not found"` | :cleanup-needed | Lookup. |
| `src/ai/miniforge/repo_dag/core.clj` | 330 | `throw ex-info "DAG not found"` | :cleanup-needed | Same. |
| `src/ai/miniforge/repo_dag/core.clj` | 337 | `throw ex-info "From repo not found"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/repo_dag/core.clj` | 340 | `throw ex-info "To repo not found"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/repo_dag/core.clj` | 344 | `throw ex-info "Self-loop not allowed"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/repo_dag/core.clj` | 348 | `throw ex-info "Edge already exists"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/repo_dag/core.clj` | 362 | `throw ex-info "Adding edge would create cycle"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/repo_dag/core.clj` | 367 | `throw ex-info "DAG not found"` | :cleanup-needed | Lookup. |
| `src/ai/miniforge/repo_dag/core.clj` | 377, 385, 390, 395, 400, 405 | `throw ex-info "DAG not found"` | :cleanup-needed | All identical lookup-failure pattern across the protocol implementation. Bulk replace with anomaly-returning protocol. |

### components/response

- **Total hits:** 22 | **Cleanup-needed:** 0 (this component *defines* the throw-anomaly! primitive)

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/response/anomaly.clj` | 20-52, 287-309, 346-352 | namespace + `throw-anomaly!` definition | :boundary | Canonical primitive. |
| `src/ai/miniforge/response/interface.clj` | 297-371 | re-exports + docstrings | :boundary | API surface. |
| `src/ai/miniforge/response/builder.clj` | 274 | docstring example | n/a | |
| `src/ai/miniforge/response/chain.clj` | 355 | docstring example with throw | n/a | |

### components/schema

- **Total hits:** 4 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/schema/interface.clj` | 80 | docstring | n/a | |
| `src/ai/miniforge/schema/interface.clj` | 84 | `throw ex-info "Schema validation failed"` | :cleanup-needed | `validate` helper — anomaly variant. Consumed widely; high-leverage cleanup. |
| `src/ai/miniforge/schema/interface.clj` | 238, 338 | docstring examples | n/a | |

### components/spec-parser

- **Total hits:** 10 | **Cleanup-needed:** 6

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/spec_parser/core.clj` | 46 | `throw ex-info "Unsupported file format"` | :cleanup-needed | Format dispatch — anomaly. |
| `src/ai/miniforge/spec_parser/core.clj` | 55 | `throw ex-info "YAML support coming soon"` | :fatal-only | Programmer-placeholder. |
| `src/ai/miniforge/spec_parser/core.clj` | 65 | `throw ex-info "Failed to parse EDN"` | :cleanup-needed | Parse error — anomaly. |
| `src/ai/miniforge/spec_parser/core.clj` | 74 | `throw ex-info "Failed to parse JSON"` | :cleanup-needed | Parse error — anomaly. |
| `src/ai/miniforge/spec_parser/core.clj` | 170 | `throw ex-info "No parser registered"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/spec_parser/core.clj` | 190 | `throw ex-info "Workflow spec must be a map"` | :cleanup-needed | Validation — anomaly. |
| `src/ai/miniforge/spec_parser/core.clj` | 194 | `throw ex-info "must have :spec/title"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/spec_parser/core.clj` | 198 | `throw ex-info "must have :spec/description"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/spec_parser/core.clj` | 230, 232 | docstrings | n/a | |
| `src/ai/miniforge/spec_parser/core.clj` | 237 | `throw ex-info "Spec file not found"` | :cleanup-needed | IO failure — anomaly. |

### components/supervisory-state

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/supervisory_state/interface.clj` | 67 | comment "throwaway stream" | n/a | False positive — word "throwaway". |
| `src/ai/miniforge/supervisory_state/accumulator.clj` | 402 | `throw ex-info "task-kanban mapping resource missing"` | :fatal-only | Boot-time. |

### components/task

- **Total hits:** 8 | **Cleanup-needed:** 5

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/task/interface.clj` | 54, 60 | docstrings | n/a | |
| `src/ai/miniforge/task/core.clj` | 131, 204, 221 | docstrings | n/a | |
| `src/ai/miniforge/task/core.clj` | 135 | `throw ex-info invalid-transition-message` | :cleanup-needed | Task FSM transition. |
| `src/ai/miniforge/task/core.clj` | 217 | `throw ex-info task-not-found-message` | :cleanup-needed | update-task lookup. |
| `src/ai/miniforge/task/core.clj` | 232 | `throw ex-info task-not-found-message` | :cleanup-needed | delete-task lookup. |
| `src/ai/miniforge/task/core.clj` | 244 | `throw ex-info task-not-found-message` | :cleanup-needed | transition-task lookup. |
| `src/ai/miniforge/task/core.clj` | 350 | `throw ex-info parent-task-not-found-message` | :cleanup-needed | decompose lookup. |

### components/task-executor

- **Total hits:** 7 | **Cleanup-needed:** 4

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/task_executor/orchestrator.clj` | 87 | `(ex-info …)` passed as failure value | :boundary | `dag/mark-failed!` data shape — already data. |
| `src/ai/miniforge/task_executor/orchestrator.clj` | 240 | `(throw e)` in catch | :boundary | Re-throw after logging. Acceptable. |
| `src/ai/miniforge/task_executor/runner.clj` | 71, 203 | docstrings | n/a | |
| `src/ai/miniforge/task_executor/runner.clj` | 78 | `throw ex-info "Failed to acquire worktree"` | :cleanup-needed | Acquisition failure — return anomaly. |
| `src/ai/miniforge/task_executor/runner.clj` | 90 | `throw ex-info "Failed to acquire environment"` | :cleanup-needed | Same. |
| `src/ai/miniforge/task_executor/runner.clj` | 215 | `throw ex-info "Spec validation failed"` | :ambiguous | Comment says "fail-fast and surface a clear error" — caller probably wants anomaly though. |

### components/tool

- **Total hits:** 1 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/tool/core.clj` | 211 | `throw ex-info "Tool ID must be namespaced keyword"` | :cleanup-needed | Constructor validation — return anomaly. |

### components/tool-registry

- **Total hits:** 4 | **Cleanup-needed:** 3

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/tool_registry/registry.clj` | 53 | `throw ex-info "Invalid tool config"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/tool_registry/registry.clj` | 57 | `throw ex-info "Tool ID must be namespaced"` | :cleanup-needed | Validation. |
| `src/ai/miniforge/tool_registry/registry.clj` | 114 | `throw ex-info "Tool not found"` | :cleanup-needed | Lookup. |
| `src/ai/miniforge/tool_registry/registry.clj` | 118 | `throw ex-info "Invalid update"` | :cleanup-needed | Validation. (Counted as 4 cleanup-needed but I previously listed 3 — corrected: 4) |

### components/tui-engine

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/tui_engine/runtime.clj` | 115 | `(throw e)` in catch InterruptedException | :boundary | Cooperative thread cancellation. Appropriate. |
| `src/ai/miniforge/tui_engine/core.clj` | 111 | docstring | n/a | "render pipeline throws" — describes catch site. |

### components/tui-views

- **Total hits:** 1 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/tui_views/prompts.clj` | 56 | `throw ex-info "Unknown prompt template"` | :cleanup-needed | Lookup — return anomaly so view can render fallback. |

### components/workflow

- **Total hits:** 26 | **Cleanup-needed:** 9 | **PR-sizing:** **split into 2 PRs**

Sub-grouping:

- PR 1 (runtime / runner): `runner.clj`, `runner_environment.clj`, `runner_cleanup.clj`, `state.clj`, `monitoring.clj`,
  `observe_phase.clj`, `agent_factory.clj`, `supervision.clj`, `dag_orchestrator.clj`
- PR 2 (loaders / registry / schemas): `loader.clj`, `chain_loader.clj`, `registry.clj`, `schemas.clj`

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/workflow/runner.clj` | 156 | `response/throw-anomaly! :anomalies.dashboard/stop` | :ambiguous | Used as a control-flow signal across slingshot try+ — *might* be intentional. See "ambiguous" section. |
| `src/ai/miniforge/workflow/runner.clj` | 293 | `response/throw-anomaly! :anomalies.workflow/no-capsule-executor` | :cleanup-needed | Pre-flight validation — return anomaly. |
| `src/ai/miniforge/workflow/runner.clj` | 389, 396, 398 | `(ex-info …)` and `:throwable &throw-context` | :boundary | Top-level workflow exception handler. Appropriate. |
| `src/ai/miniforge/workflow/runner_environment.clj` | 75 | `response/throw-anomaly! :anomalies.executor/unavailable` | :cleanup-needed | Pre-flight — return anomaly. |
| `src/ai/miniforge/workflow/runner_environment.clj` | 115 | docstring | n/a | |
| `src/ai/miniforge/workflow/runner_environment.clj` | 132 | `(throw e)` in catch when governed | :boundary | Re-throw — acceptable. |
| `src/ai/miniforge/workflow/runner_cleanup.clj` | 108 | `(ex-info …)` as event payload | :boundary | Wrapped as event data. |
| `src/ai/miniforge/workflow/state.clj` | 103 | docstring | n/a | |
| `src/ai/miniforge/workflow/state.clj` | 111 | `response/throw-anomaly! :anomalies.workflow/invalid-transition` | :cleanup-needed | FSM transition — return validation result. |
| `src/ai/miniforge/workflow/monitoring.clj` | 53 | `(ex-info …)` constructor | :ambiguous | Used at line 66. |
| `src/ai/miniforge/workflow/monitoring.clj` | 66 | `(throw (unsupported-supervisor-exception …))` | :fatal-only | Boot-time supervisor type check; exhaustive. |
| `src/ai/miniforge/workflow/observe_phase.clj` | 69 | `response/throw-anomaly! :anomalies/not-found` | :fatal-only | Boot-time resource. |
| `src/ai/miniforge/workflow/agent_factory.clj` | 64 | `throw ex-info "requires :phase/handler"` | :fatal-only | Boot-time validation. |
| `src/ai/miniforge/workflow/agent_factory.clj` | 85 | `throw ex-info "Unknown agent type"` | :fatal-only | Exhaustive case. |
| `src/ai/miniforge/workflow/supervision.clj` | 42 | `throw ex-info "Missing supervision config"` | :fatal-only | Boot-time. |
| `src/ai/miniforge/workflow/dag_orchestrator.clj` | 421 | `(throw t)` in cleanup catch | :boundary | Re-throw after future cancellation. |
| `src/ai/miniforge/workflow/loader.clj` | 136 | `response/throw-anomaly! :anomalies/fault` | :cleanup-needed | EDN load failure — return anomaly. |
| `src/ai/miniforge/workflow/loader.clj` | 185, 222 | docstrings | n/a | |
| `src/ai/miniforge/workflow/loader.clj` | 192 | `response/throw-anomaly! :anomalies.workflow/invalid-config` | :cleanup-needed | Validation. |
| `src/ai/miniforge/workflow/loader.clj` | 239 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Lookup — return anomaly. |
| `src/ai/miniforge/workflow/chain_loader.clj` | 114 | docstring | n/a | |
| `src/ai/miniforge/workflow/chain_loader.clj` | 126 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Lookup. |
| `src/ai/miniforge/workflow/registry.clj` | 99 | `response/throw-anomaly! :anomalies/fault` | :cleanup-needed | Load failure — return anomaly. |
| `src/ai/miniforge/workflow/registry.clj` | 160 | `response/throw-anomaly! :anomalies.workflow/invalid-config` | :cleanup-needed | Validation. |
| `src/ai/miniforge/workflow/registry.clj` | 178 | `response/throw-anomaly! :anomalies.workflow/invalid-config` | :cleanup-needed | Validation. |
| `src/ai/miniforge/workflow/registry.clj` | 196 | `response/throw-anomaly! :anomalies/incorrect` | :cleanup-needed | Validation. |
| `src/ai/miniforge/workflow/schemas.clj` | 163 | `throw ex-info "Invalid checkpoint data"` | :cleanup-needed | Validation. |

### components/workflow-resume

- **Total hits:** 4 | **Cleanup-needed:** 3

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/workflow_resume/core.clj` | 119 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Should return anomaly result. |
| `src/ai/miniforge/workflow_resume/core.clj` | 288 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Same. |
| `src/ai/miniforge/workflow_resume/schema.clj` | 78 | docstring | n/a | |
| `src/ai/miniforge/workflow_resume/schema.clj` | 84 | `throw ex-info` | :cleanup-needed | `validate!` helper — anomaly variant. |

---

## Per-base breakdown

### bases/cli

- **Total hits:** 18 | **Cleanup-needed:** 5 | **PR-sizing:** single PR

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/cli/messages.clj` | 89 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Missing message key — should return anomaly. |
| `src/ai/miniforge/cli/workflow_runner/context.clj` | 40 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | File-not-found — return anomaly. |
| `src/ai/miniforge/cli/workflow_runner/context.clj` | 48 | `response/throw-anomaly! :anomalies/unsupported` | :cleanup-needed | Format check — return anomaly. |
| `src/ai/miniforge/cli/workflow_runner/context.clj` | 57 | `response/throw-anomaly! :anomalies/fault` | :cleanup-needed | Parse failure — return anomaly. |
| `src/ai/miniforge/cli/workflow_runner.clj` | 126 | `response/throw-anomaly! :anomalies/not-found` | :cleanup-needed | Lookup. |
| `src/ai/miniforge/cli/workflow_runner.clj` | 130 | `response/throw-anomaly! :anomalies.workflow/invalid-config` | :cleanup-needed | Validation. |
| `src/ai/miniforge/cli/workflow_runner.clj` | 202, 264, 269, 318, 348, 428, 432, 553, 575 | `(throw e)` rethrows from CLI catch blocks | :boundary | CLI top-level — print red error, then rethrow for non-zero exit. Appropriate for CLI base. |
| `src/ai/miniforge/cli/main/commands/run.clj` | 146, 158 | `:throwable &throw-context` | :boundary | CLI catch + exit. |
| `src/ai/miniforge/cli/main/commands/resume.clj` | 157 | `(throw e)` rethrow | :boundary | CLI rethrow. |
| `src/ai/miniforge/cli/main/commands/plan_executor.clj` | 146 | `(throw e)` rethrow | :boundary | CLI rethrow. |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 91 | `throw ex-info "Could not find pipelines/*.edn"` | :cleanup-needed | CLI arg validation — print + exit pattern (or return anomaly). Mid-priority. |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 97 | `throw ex-info "Not a pack directory"` | :cleanup-needed | Same. |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 102, 123 | docstrings | n/a | |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 106 | `throw ex-info "missing --env"` | :cleanup-needed | Same. |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 115 | `throw ex-info "env not found"` | :cleanup-needed | Same. |
| `src/ai/miniforge/cli/main/commands/etl.clj` | 118 | `throw ex-info "--env was a name but pipeline given"` | :cleanup-needed | Same. |

### bases/etl

- **Total hits:** 1 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/etl/main.clj` | 42 | docstring "throws without an encoder" | n/a | Describes JSON encoder behaviour. |

### bases/lsp-mcp-bridge

- **Total hits:** 2 | **Cleanup-needed:** 0

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/lsp_mcp_bridge/installer.clj` | 93 | `throw ex-info "Download failed"` | :ambiguous | One-shot installer — could be CLI-style (boundary) but currently only used in setup tooling. |
| `src/ai/miniforge/lsp_mcp_bridge/mcp/server.clj` | 68 | `throw ex-info "Method not found" :code -32601` | :boundary | MCP method dispatch — JSON-RPC protocol mandates an error response built from this. Acceptable. |

### bases/mcp-context-server

- **Total hits:** 7 | **Cleanup-needed:** 1

| File | Line | Pattern | Category | Notes |
|------|------|---------|----------|-------|
| `src/ai/miniforge/mcp_context_server/tools.clj` | 44 | `throw ex-info "Unknown validator"` | :fatal-only | Boot-time / programmer-error in EDN config. |
| `src/ai/miniforge/mcp_context_server/tools.clj` | 52, 59 | doc + `throw ex-info :code -32602` | :boundary | MCP param validation maps directly to JSON-RPC error code. Boundary. |
| `src/ai/miniforge/mcp_context_server/tools.clj` | 86 | `throw ex-info :tool/no-handler` | :fatal-only | Boot/registry programmer error. |
| `src/ai/miniforge/mcp_context_server/tools.clj` | 98 | `throw ex-info "Tool registry config not found"` | :fatal-only | Boot-time resource. |
| `src/ai/miniforge/mcp_context_server/tools.clj` | 148 | `throw ex-info :tool/unknown :code -32601` | :boundary | MCP method-not-found error. Boundary. |
| `src/ai/miniforge/mcp_context_server/server.clj` | 62 | `throw ex-info "Method not found" :code -32601` | :boundary | Same. |

---

## Recommended cleanup ordering

Tackle in this order. Earlier rounds enable simpler/cleaner downstream work:

1. **Foundation cleanup (week 1, high-leverage)** — `schema/interface.clj` `validate`, `dag-primitives/result.clj`
  `unwrap`, `connector/retry.clj`, plus the `validate!` helper in every connector schema namespace (`connector-jira`,
  `connector-gitlab`, `connector-pipeline-output`, `workflow-resume/schema`). These are called by everyone; once they
  return anomalies, downstream sites can be migrated mechanically.
2. **High-density components (week 2)** — `repo-dag` (12 cleanup), `spec-parser` (6), `operator` (5), `task` (5).
3. **Connector family (week 3)** — bulk PR or one-per-connector, since the patterns are nearly identical:
  `connector-jira`, `connector-gitlab`, `connector-github`, `connector-excel`, `connector-edgar`, `connector-file`,
  `connector-http`, `connector-sarif`, `connector-pipeline-output`. The `require-handle!` and `validate-auth!` helpers
  are duplicated across all of them — consider extracting to `connector` core.
4. **Workflow surface (week 4)** — split into 2 PRs: runner-side first, then loader/registry side.
5. **Mid-density components (week 5)** — `pr-lifecycle/controller.clj`, `event-stream/listeners.clj`, `tool-registry`,
  `policy-pack`, `gate`, `gate-classification`, `agent` (planner + meta_protocol + file_artifacts).
6. **CLI base + remainder (week 6)** — `bases/cli` etl/workflow_runner argument validation. Note CLI top-level `(throw
  e)` rethrows are boundary and stay.
7. **Decide ambiguous (parallel, ongoing)** — `:anomalies.dashboard/stop` flow-control throws are likely intentional and
  may stay.

## Ambiguous cases needing human review

These need a judgment call before any of them is rewritten. Listing the most interesting:

1. `components/workflow/src/ai/miniforge/workflow/runner.clj:156` — `response/throw-anomaly! :anomalies.dashboard/stop`
  is caught by a slingshot `try+` at line 387 to implement *cooperative cancellation* of a running pipeline. Is this
  intentional control-flow-via-exception (and therefore acceptable boundary), or should it become a checked atom-flag
  the runner polls between phases?
2. `components/connector-http/src/ai/miniforge/connector_http/request.clj:75-79` — `throw-on-failure!` is a public
  helper used by every connector (`connector-jira`, `connector-gitlab`, `connector-github`, `connector-edgar`, etc.) to
  short-circuit pagination loops on HTTP failure. Do we keep it (label its callers `:fatal-only`) or replace with `(let
  [r (do-request …)] (if (:success? r) r (return-anomaly)))` everywhere? Decision affects ~20 sites in connectors.
3. `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/docker.clj:489-495` — the executor's
  `container-exec-fn` has a `:throw-on-error?` flag. This is a pre-existing knob exposing both behaviours. Should we
  just delete the throwing branch and have all callers go through anomaly results? (Caller `line 554` passes `true`.)
4. `components/agent/src/ai/miniforge/agent/prompts.clj:47, 52, 70` — `throw-anomaly!` for missing prompt resources is
  currently treated as fatal at startup. Could conceivably allow agents to be constructed with a fallback prompt and
  only fail at first invocation — design call.
5. `components/phase-software-factory/src/ai/miniforge/phase_software_factory/implement.clj:105` and `verify.clj:159` —
  phase pre-flight throws (no worktree / no environment). Phase pipelines have a `response/failure` shape; should these
  be returned as failed-phase results so the phase orchestrator can record them in `execution/errors` like every other
  phase failure?
6. `components/task-executor/src/ai/miniforge/task_executor/runner.clj:215` — explicit comment "Throws ex-info on
  failure so the caller can fail-fast and surface a clear error". This is a deliberate design choice; flagging for
  re-confirmation rather than auto-rewriting.
7. `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge/installer.clj:93` — installer error. Whether installer is a
  CLI/boundary depends on whether it's invoked via `bb installer` (boundary) or also embedded in dashboard setup flows
  (caller).

---

## Notes on test-file impact

The 479 component test hits and ~14 base test hits are predominantly:

- `is (thrown? clojure.lang.ExceptionInfo …)` matchers — must change to `(is (= :anomalies/x (:anomaly/category
  result)))` style as production sites are migrated.
- `(catch ExceptionInfo …)` in test setup — same.
- A handful of true production-style throws inside test helpers (e.g. mock backends) — flag during the related
  production PR.

Highest test-coupling components (where production cleanup will require the most test rework): `tui-views/persistence`
(45+ hits across `github_acceptance_test.clj`, `github_extended_test.clj`), `task` (18), `response` (35 across
`interface_test.clj` and `throw_anomaly_test.clj`, but those *are* the canonical tests for `throw-anomaly!`),
`workflow/runner_iteration_test` (13), `agent/invoke_error_shape_test` (13).

Note that `tui-views/persistence/github_*` tests show ~52 throw-related hits but the production `tui-views/src/` has
only **1** throw site (`prompts.clj:56`). This suggests those tests are exercising a `connector-github`/`pr-sync`
boundary; double-check during cleanup that the throw they assert against actually originates in a `tui-views` namespace,
not a downstream component.
