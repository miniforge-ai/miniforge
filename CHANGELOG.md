<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Changelog

All notable changes to Miniforge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Each versioned release also records the merged PR inventory that landed in that release.
Structured linked release inventory begins with `2026.04.26.1`; earlier tagged
releases were not consistently maintained in this file.

## [Unreleased]

### Added

### Fixed

### Changed

<!-- markdownlint-disable MD013 -->
## [2026.04.26.1] — 2026-04-26

Stable tags: `stable-20260426`, `v2026.04.26.1`

### Added

- Workflow FSM coverage now includes machine-authoritative execution, supervision, PR lifecycle control, snapshot
  persistence, registration-time reachability checks, and configurable-workflow execution on the same compiled machine
  path.
- Supervisory-state and operator capabilities expanded with linked task and decision projections, intervention lifecycle
  support, and richer streaming/event surfaces.
- Data Foundry gained `mf etl run`, `mf etl list`, and `mf etl validate` entry points, while Windows native preview and
  Scoop packaging landed for cross-platform distribution.
- New platform and workflow infrastructure landed for `bb` utilities, workflow-security-compliance, PR scoring, coverage
  prefetch, and release dispatch automation.

### Fixed

- DAG execution now emits consistent skip diagnostics, preserves inner-result failure context, restores resume data from
  the authoritative workflow location, and converges DAG resume on machine snapshots.
- Agent/runtime hygiene fixes addressed canonical error shapes, planner convergence/write failures, stale implementer
  session tracking, CLI/status surface regressions, and persistent pre-commit lint noise.
- Release and CI fixes restored test coverage, unmasked piped test failures on Linux, and kept downstream release
  automation firing on stable publishes.

### Changed

- The workflow/control plane is now formalized around explicit state machines across execution, supervision,
  loop/task/lightweight lifecycles, and PR monitoring.
- Configurable workflows no longer rely on legacy sidecar state progression; validation, normalization, checkpointing,
  and phase selection all compile through the same workflow-machine authority.
- Agent/configuration defaults, retry policies, lifecycle definitions, and other operational settings were pulled
  further into EDN-backed configuration and localized message catalogs.

### Merged PRs

- [#571](https://github.com/miniforge-ai/miniforge/pull/571) fix(workflow): emit `:workflow/dag-considered` with skip
  reason
- [#572](https://github.com/miniforge-ai/miniforge/pull/572) feat(supervisory-state): `attach!` helper + wire into all
  event streams
- [#573](https://github.com/miniforge-ai/miniforge/pull/573) fix(workflow): enrich `:dag-considered` skip events with
  result shape
- [#574](https://github.com/miniforge-ai/miniforge/pull/574) fix(workflow): propagate inner-result failure to phase
  outcome + surface `:result/error` + checkpoint/resume spec
- [#575](https://github.com/miniforge-ai/miniforge/pull/575) fix(agent): canonical error shape + planner prompt closing
  contract
- [#576](https://github.com/miniforge-ai/miniforge/pull/576) spec: clj-xref + tree-sitter symbols for richer agent
  context
- [#577](https://github.com/miniforge-ai/miniforge/pull/577) spec: work-spec prioritization + themes + authoring
  standard
- [#578](https://github.com/miniforge-ai/miniforge/pull/578) fix(deps): register supervisory-state + mcp-context-server
  in CLI projects
- [#579](https://github.com/miniforge-ai/miniforge/pull/579) chore(standards): bump submodule to main after 021 merge
- [#580](https://github.com/miniforge-ai/miniforge/pull/580) fix(bb): add supervisory-state to `bb miniforge` task
  classpath
- [#581](https://github.com/miniforge-ai/miniforge/pull/581) fix(agent): bump max-tokens to 32k + spec OPSV convergence
  for agent budgets
- [#582](https://github.com/miniforge-ai/miniforge/pull/582) fix(agent): planner defaults to claude-opus-4-6 + spec
  multi-backend CLI parity
- [#583](https://github.com/miniforge-ai/miniforge/pull/583) fix(planner): raise max-turns 40→80 + sharpen
  stop-exploring guidance
- [#584](https://github.com/miniforge-ai/miniforge/pull/584) feat(events): `:agent/tool-call` events with named tools +
  `mf events show` CLI
- [#586](https://github.com/miniforge-ai/miniforge/pull/586) fix(tests): update two integration tests out of sync with
  main
- [#587](https://github.com/miniforge-ai/miniforge/pull/587) spec: planner convergence + pedestal interceptor chain
- [#588](https://github.com/miniforge-ai/miniforge/pull/588) Extract shared Babashka helpers into `projects/bb-utils`
  polylith (1/5)
- [#589](https://github.com/miniforge-ai/miniforge/pull/589) Add `bb-config` + `bb-generate-icon` components (2/5)
- [#590](https://github.com/miniforge-ai/miniforge/pull/590) Add `bb-r2` R2 upload primitive (3/5)
- [#591](https://github.com/miniforge-ai/miniforge/pull/591) Add `bb-data-plane-http` primitive (4/5)
- [#592](https://github.com/miniforge-ai/miniforge/pull/592) Add `bb-adapter-thesium-risk` for publish orchestration
  (5/5)
- [#593](https://github.com/miniforge-ai/miniforge/pull/593) feat(planner): container-promoted plan artifact via
  `.miniforge/plan.edn`
- [#594](https://github.com/miniforge-ai/miniforge/pull/594) feat(planner): disallow native
  `Read`/`Bash`/`Grep`/`Glob`/`Agent`/`LS`
- [#595](https://github.com/miniforge-ai/miniforge/pull/595) refactor(response): `success?`/`error?` predicates + fix
  pre-existing implement test failures
- [#596](https://github.com/miniforge-ai/miniforge/pull/596) refactor(connector): extract retry-policy presets to EDN
- [#597](https://github.com/miniforge-ai/miniforge/pull/597) feat(llm): capture `stop_reason` + `num_turns` from Claude
  `stream-json`
- [#598](https://github.com/miniforge-ai/miniforge/pull/598) spec(N5-delta-2): pre-computed PR readiness/risk/policy
  scoring
- [#599](https://github.com/miniforge-ai/miniforge/pull/599) refactor(agent): extract per-role static defaults to EDN
- [#600](https://github.com/miniforge-ai/miniforge/pull/600) refactor(agent): extract per-create-fn LLM call defaults to
  EDN
- [#601](https://github.com/miniforge-ai/miniforge/pull/601) refactor(agent): extract magic numbers to named constants
- [#602](https://github.com/miniforge-ai/miniforge/pull/602) fix(agent): use `mcp__<server>__<tool>` in `--allowedTools`
  (iter 5-10 root cause)
- [#603](https://github.com/miniforge-ai/miniforge/pull/603) refactor: flatten nested conditionals + DRY readiness
  factors
- [#604](https://github.com/miniforge-ai/miniforge/pull/604) refactor(connector-sarif): fix `deps.edn` key, adopt
  retry-policy preset
- [#605](https://github.com/miniforge-ai/miniforge/pull/605) fix(submodule): `.standards` URL from SSH to HTTPS
- [#606](https://github.com/miniforge-ai/miniforge/pull/606) test: use `response/success?` predicate across
  response-builder test sites
- [#607](https://github.com/miniforge-ai/miniforge/pull/607) refactor(pipeline-runner): DRY
  `:started-at`/`:completed-at` across stage executors
- [#608](https://github.com/miniforge-ai/miniforge/pull/608) feat(pr-scoring): producer-side PR readiness/risk/policy
  scoring (N5-δ2)
- [#609](https://github.com/miniforge-ai/miniforge/pull/609) fix(bb-adapter-thesium-risk): invoke `ai.thesium.etl.cli`
  (lives in consumer)
- [#610](https://github.com/miniforge-ai/miniforge/pull/610) fix: planner convergence — unblock `plan.edn` Write +
  surface error context
- [#611](https://github.com/miniforge-ai/miniforge/pull/611) spec(N5-delta-3): observational entities for
  Evidence/Artifact/Task/Decision/Pack views
- [#612](https://github.com/miniforge-ai/miniforge/pull/612) refactor(polylith): unique interfaces + hygiene pass for
  phase and data-foundry
- [#613](https://github.com/miniforge-ai/miniforge/pull/613) refactor(polylith): clear Error 107 + Warning 207 — project
  membership
- [#614](https://github.com/miniforge-ai/miniforge/pull/614) spec(N5-δ3): revise — full evidence bundle, open statuses,
  pack CRUD in scope
- [#615](https://github.com/miniforge-ai/miniforge/pull/615) feat(implementer): port planner-convergence fixes —
  artifact wins on CLI error
- [#616](https://github.com/miniforge-ai/miniforge/pull/616) feat(supervisory-state): `TaskNode` +
  `:supervisory/task-node-upserted` (N5-δ3 §2.3)
- [#617](https://github.com/miniforge-ai/miniforge/pull/617) fix(resume): read per-event JSON from workflow dir, not a
  missing `.edn`
- [#618](https://github.com/miniforge-ai/miniforge/pull/618) refactor(supervisory-state): materialized-view framing,
  remove replay, drop stream-atom peek
- [#619](https://github.com/miniforge-ai/miniforge/pull/619) feat(supervisory-state): `DecisionCard` +
  `:supervisory/decision-upserted` (N5-δ3 §2.4)
- [#620](https://github.com/miniforge-ai/miniforge/pull/620) GROUP 3: Formalize diagnostic meta on LLM streaming
  responses
- [#621](https://github.com/miniforge-ai/miniforge/pull/621) feat(etl): `mf etl run|list|validate` for Data Foundry
  packs
- [#622](https://github.com/miniforge-ai/miniforge/pull/622) spec: tech-registry-driven doctor bootstrap (runtime-dep
  gate)
- [#630](https://github.com/miniforge-ai/miniforge/pull/630) refactor: classify JVM-only bricks via ns-meta; drop
  requiring-resolve
- [#631](https://github.com/miniforge-ai/miniforge/pull/631) fix(phase): close empty-diff path at curator, review, and
  release
- [#632](https://github.com/miniforge-ai/miniforge/pull/632) refactor: swap hato for `org.babashka/http-client`; drop 4
  jvm-only markers
- [#633](https://github.com/miniforge-ai/miniforge/pull/633) refactor(connector-edgar): XML parsing via
  `clojure.data.xml`; drops last non-POI jvm-only marker
- [#634](https://github.com/miniforge-ai/miniforge/pull/634) fix(agent): untrack implementer session-id from git, move
  to `.miniforge/`
- [#635](https://github.com/miniforge-ai/miniforge/pull/635) fix: restore dogfood and status CLI surfaces
- [#636](https://github.com/miniforge-ai/miniforge/pull/636) docs: formalize workflow supervision machine specs
- [#637](https://github.com/miniforge-ai/miniforge/pull/637) fix: clear persistent `clj-kondo` warnings
- [#638](https://github.com/miniforge-ai/miniforge/pull/638) refactor: make workflow execution machine authoritative
- [#639](https://github.com/miniforge-ai/miniforge/pull/639) refactor: normalize phase transition requests
- [#640](https://github.com/miniforge-ai/miniforge/pull/640) fix: tighten phase outcome standards follow-up
- [#641](https://github.com/miniforge-ai/miniforge/pull/641) refactor: extract workflow supervision boundary
- [#642](https://github.com/miniforge-ai/miniforge/pull/642) fix: remove duplicated workflow supervision payload maps
- [#644](https://github.com/miniforge-ai/miniforge/pull/644) feat: persist workflow machine snapshots
- [#645](https://github.com/miniforge-ai/miniforge/pull/645) feat: formalize workflow supervision fsm
- [#646](https://github.com/miniforge-ai/miniforge/pull/646) feat: formalize PR lifecycle controller FSM
- [#647](https://github.com/miniforge-ai/miniforge/pull/647) docs: pack interchange, control surface, and per-workflow
  streaming amendments
- [#648](https://github.com/miniforge-ai/miniforge/pull/648) feat: formalize workflow agent default contract
- [#649](https://github.com/miniforge-ai/miniforge/pull/649) refactor: make agent meta loop authoritative
- [#650](https://github.com/miniforge-ai/miniforge/pull/650) refactor: make standard workflow phase selection
  machine-driven
- [#651](https://github.com/miniforge-ai/miniforge/pull/651) feat: add `bb ccov` and bootstrap coverage prefetch
- [#652](https://github.com/miniforge-ai/miniforge/pull/652) feat: add workflow FSM reachability guards
- [#653](https://github.com/miniforge-ai/miniforge/pull/653) chore: gitignore `work/failed/` — only `:not-started` and
  `:finished` belong in git
- [#654](https://github.com/miniforge-ai/miniforge/pull/654) refactor: unblock CLI web coverage instrumentation
- [#655](https://github.com/miniforge-ai/miniforge/pull/655) feat: emit workflow-owned PR and artifact projections
- [#656](https://github.com/miniforge-ai/miniforge/pull/656) refactor: move configurable workflows onto compiled machine
- [#657](https://github.com/miniforge-ai/miniforge/pull/657) docs: windows fidelity pass — platform support, Scoop
  install
- [#658](https://github.com/miniforge-ai/miniforge/pull/658) feat: windows native preview — cross-platform `bb.edn` +
  CI/release
- [#659](https://github.com/miniforge-ai/miniforge/pull/659) chore: Scoop bucket template — autoupdate manifest +
  workflow
- [#660](https://github.com/miniforge-ai/miniforge/pull/660) refactor: unify workflow validator and compiler
- [#661](https://github.com/miniforge-ai/miniforge/pull/661) ci(linux): install Polylith + unmask tee'd test failures
  with `pipefail`
- [#662](https://github.com/miniforge-ai/miniforge/pull/662) ci(release): fire `repository_dispatch` to Scoop bucket on
  each release
- [#663](https://github.com/miniforge-ai/miniforge/pull/663) fix: re-enable tests deferred from PR `#661` — both real
  bugs resolved
- [#664](https://github.com/miniforge-ai/miniforge/pull/664) refactor: converge DAG resume on machine snapshots
- [#665](https://github.com/miniforge-ai/miniforge/pull/665) refactor: align loop and task lifecycles with FSM defs
- [#666](https://github.com/miniforge-ai/miniforge/pull/666) refactor: formalize lightweight lifecycle state machines
- [#667](https://github.com/miniforge-ai/miniforge/pull/667) feat: move configurable workflows onto execution FSM
- [#668](https://github.com/miniforge-ai/miniforge/pull/668) refactor: use checkpoints as DAG resume authority
- [#669](https://github.com/miniforge-ai/miniforge/pull/669) refactor: remove legacy state runtime dependency
<!-- markdownlint-enable MD013 -->

## [0.1.0] — 2026-01-01

Initial internal release.

- Standard SDLC workflow: plan → implement → verify → review → release → observe
- Agent runtime: planner, implementer, reviewer, tester, releaser
- Policy gates: syntax, lint, no-secrets, tests-pass, coverage, review-approved
- Event stream with append-only semantics and file subscription
- CLI (`mf run`, `mf workflow`, `mf tui`) via Babashka
- Web dashboard with htmx live updates
- Polylith monorepo architecture
