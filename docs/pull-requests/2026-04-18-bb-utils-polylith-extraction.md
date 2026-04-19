<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Extract shared Babashka helpers into a miniforge Polylith project

## Context

Every miniforge umbrella product (thesium-risk a.k.a. risk-dashboard,
thesium-career, and the products still to scaffold) needs the same core
Babashka task helpers: repo-root resolution, status output, subprocess
wrappers, and a test-namespace discovery/runner. Today each product
re-implements them — risk-dashboard has `tasks/paths.clj`,
`tasks/out.clj`, `tasks/proc.clj`, and `test/test_runner.clj`; miniforge
itself has parallel implementations. Drift is already visible (e.g.
risk-dashboard's `proc/destroy!` uses a 5-second dereference timeout,
others don't).

The decision: ship one canonical set of helpers as a Polylith project
inside miniforge (`projects/bb-utils`), with the four helpers split into
four small components so products can depend on any subset if the shared
version drifts later. The distribution mechanism for umbrella products
is a git submodule pointed at miniforge (so `:local/root
"../miniforge/projects/bb-utils"`-style entries resolve against a
submodule path like `.miniforge-bb-utils/projects/bb-utils`). This is
**not** a `:git/sha` Maven-style dep — the submodule keeps the tree
browsable and editable inside each consumer.

This is **PR 1 of a 4-PR series**:

1. **This PR** — extract helpers into `components/bb-*` + `projects/bb-utils`, wire into `workspace.edn` and root
  `deps.edn`. No consumer migrations.
2. Migrate miniforge's own `bb.edn` to consume bb-utils via the new project alias.
3. Add miniforge as a submodule in thesium-career and swap its temporary `:local/root` entries.
4. Migrate thesium-risk (risk-dashboard) to consume bb-utils via submodule.

## What changed

### 1. Four new components under `components/bb-*`

Each follows the standard Polylith layout: `src/ai/miniforge/<name>/{interface.clj,core.clj}`,
`test/ai/miniforge/<name>/core_test.clj`, `deps.edn`. Interfaces are thin pass-throughs per standard 210.

| Component | Layer 0 | Layer 1 | Layer 2 |
|---|---|---|---|
| `bb-paths` | `find-up` (pure walk-up search) | `repo-root`, `under-root` | `ensure-dir!`, `tmp-dir!`, `delete-tree!` |
| `bb-out` | `format-section`, `format-step`, `format-ok`, `format-warn`, `format-fail` (pure formatters) | `section`, `step`, `ok`, `warn`, `fail` (printers — warn/fail → `*err*`) | — |
| `bb-proc` | `split-opts` (pure `[opts cmd]` normalization) | `run!`, `run-bg!`, `sh`, `installed?`, `destroy!` | — |
| `bb-test-runner` | `path->ns-symbol` (pure path → symbol) | `discover-test-namespaces` + private `classpath-test-roots` | `run-all` (requires namespaces, runs `clojure.test`, `System/exit` on fail or error) |

### 2. Aggregator project `projects/bb-utils/deps.edn`

Pulls the four components via `:local/root` and exposes a `:test` alias
that sweeps every component's `test` dir into a single run under the
cognitect test-runner. This is what umbrella products will alias via
submodule once PRs 2–4 land.

### 3. Workspace + root dev/test wiring

- `workspace.edn` — registers the aggregator project with alias `bb-utils`.
- Root `deps.edn` — appends the four components to `:dev :extra-paths`, `:dev :extra-deps`, `:test :extra-paths`, and
  `:test :extra-deps` under a `;; bb-utils (shared Babashka utilities)` marker in each block.

No existing miniforge code is modified. No existing bb task is rewired yet.

### 4. Babashka / JVM dual-loadability

`bb-test-runner/core.clj` calls `babashka.classpath/get-classpath` lazily
via `requiring-resolve` so the namespace itself loads under plain JVM
Clojure — required so `clj -T:polylith test` can exercise the pure
discovery helpers (`path->ns-symbol`, `discover-test-namespaces`) under
the JVM while `run-all` only resolves at runtime under Babashka. Matches
the pattern already in `risk-dashboard/test/test_runner.clj`.

## Self-review against standards

### 001 — Stratified design

Every `core.clj` file is labeled `Layer 0/1/2` and built concrete-upward
with no cross-layer cycles. Layer counts per file:

- `bb-paths/core.clj` — 3 layers (pure → repo-root → side-effect). Within cap.
- `bb-out/core.clj` — 2 layers (pure → printers). Under cap.
- `bb-proc/core.clj` — 2 layers (pure arg split → process ops). Under cap.
- `bb-test-runner/core.clj` — 3 layers (pure ns-symbol → discover → run-all). Within cap.

Component-level DAG: the four components are peers with no
inter-dependencies, so the Polylith DAG remains acyclic.

### 210 — Clojure

- Each `interface.clj` is a pass-through to `core`; no implementation leaks into the interface.
- Every `.clj` ends in a `(comment ... :leave-this-here)` rich-comment form with REPL-friendly invocations.
- No `(or (:k m) default)` anti-pattern — these helpers take positional args or opts-maps; there are no
  map-lookup-with-default sites that would trigger the rule.
- Every public fn has a docstring.

### 310 — Polylith

- Component layout follows `components/<name>/{src,test,resources,deps.edn}`; interface ns is
  `ai.miniforge.bb-<name>.interface`; underscore-to-hyphen convention applied in source paths (`bb_paths` ↔ `bb-paths`).
- Unit tests live inside each component's `test/` dir per the framework standard.
- Aggregator project declared in `workspace.edn`; `projects/bb-utils/deps.edn` pulls components via `:local/root`.
- No cross-component references — each component has a single-responsibility API.

### 400 — Testing

- Factory functions (`with-scratch`, `touch-bb-edn`, `with-test-tree`, `capture-err`) grouped at Layer 0 of each test
  file.
- Test names follow `test-<behavior>` convention; `testing "given X → Y"` block format used consistently.
- No network I/O. Filesystem writes go only to process-unique `create-temp-dir` scratch dirs; factories wrap cleanup in
  `(try ... (finally ...))` so failures still tear down.
- No `Thread/sleep` hacks. The one bounded deref — `destroy!` → `deref proc 5000 nil` — is the subprocess-teardown
  deadline, not a test timing hack; `run-bg!`+`destroy!` typically completes in <100ms on a healthy host.
- `run-all` is not unit-tested (calls `System/exit`); only the pure helpers it composes are. This is noted in the
  `core.clj` docstring.

### 810 — Header / copyright

All 12 new `.clj` files open with the canonical 17-line Apache 2.0
block (lines 1–17), a blank line 18, and the `(ns ...)` form starting at
line 19. Fields match the project convention: Title `Miniforge.ai`,
Subtitle `An agentic SDLC / fleet-control platform`, Author `Christopher
Lester`, Line `Founder, Miniforge.ai (project)`, Copyright `2025-2026
Christopher Lester (christopher@miniforge.ai)`.

## Files touched

```text
A  components/bb-paths/deps.edn
A  components/bb-paths/src/ai/miniforge/bb_paths/interface.clj
A  components/bb-paths/src/ai/miniforge/bb_paths/core.clj
A  components/bb-paths/test/ai/miniforge/bb_paths/core_test.clj
A  components/bb-out/deps.edn
A  components/bb-out/src/ai/miniforge/bb_out/interface.clj
A  components/bb-out/src/ai/miniforge/bb_out/core.clj
A  components/bb-out/test/ai/miniforge/bb_out/core_test.clj
A  components/bb-proc/deps.edn
A  components/bb-proc/src/ai/miniforge/bb_proc/interface.clj
A  components/bb-proc/src/ai/miniforge/bb_proc/core.clj
A  components/bb-proc/test/ai/miniforge/bb_proc/core_test.clj
A  components/bb-test-runner/deps.edn
A  components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj
A  components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj
A  components/bb-test-runner/test/ai/miniforge/bb_test_runner/core_test.clj
A  projects/bb-utils/deps.edn
M  workspace.edn
M  deps.edn
A  docs/pull-requests/2026-04-18-bb-utils-polylith-extraction.md
```

## Out of scope (tracked as follow-ups)

- Migrating miniforge's own `bb.edn` to consume the new interfaces (PR 2).
- Adding miniforge as a submodule in thesium-career and swapping its `:local/root "../miniforge/projects/bb-utils"` for
  the submodule path (PR 3).
- Migrating risk-dashboard / thesium-risk onto the shared helpers (PR 4).
- Deleting the now-duplicated `tasks/paths.clj`, `tasks/out.clj`, `tasks/proc.clj`, `test/test_runner.clj` from each
  umbrella product (concurrent with PRs 2 and 4).
