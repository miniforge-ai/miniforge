# Memory Substrate Port — Cross-Repo Refactor Plan

**Status:** Draft, ready for agent execution
**Date:** 2026-04-26
**Scope:** Port the response-chain pattern from ixi into thesium-workflows, build a consumer-RAG-shaped evidence-record component, wire them together, and apply a standing "exceptions are data" rule across miniforge.

## Goal

Bring the agentic memory substrate's runtime-trace and audit patterns into thesium-workflows under its newer architectural opinions (malli, colocated schemas, `ai.thesium.*` namespacing, layer-labeled imports), without copying ixi code wholesale. Apply the underlying error-discipline rule across miniforge to halt and reverse the "agents love exceptions" regression.

## Repos affected

| Repo | Path | Role in this refactor |
|------|------|------------------------|
| `miniforge` (OSS, Apache 2) | `/Users/chris/ws/miniforge.ai/miniforge` | **Shared substrate** — gains new common components (`anomaly`, `response-chain`, `boundary`, `content-hash`) plus exceptions-as-data cleanup. Already vendored by every other repo, so nothing extracted, nothing relocated outside. |
| `thesium-workflows` | `/Users/chris/ws/miniforge.ai/thesium-workflows` | Consumes the new miniforge components; builds `inference-evidence` + memory-operations |
| `miniforge-fleet` | *<add path>* | Consumes the new miniforge components. **TODO (user):** add Fleet-specific cleanup tasks once scope is captured. |
| `miniforge-standards` (pack source) | `/Users/chris/ws/miniforge.ai/miniforge-standards` | New standing rule (exceptions-as-data) |
| `engrammicai/ixi` | `/Users/chris/ws/engrammicai/ixi` | **Read-only reference.** Do not commit. |

**Architectural rule:** *miniforge OSS is the substrate. Other repos already pull it in.* When something is genuinely cross-cutting Clojure infrastructure, it goes into miniforge as a new component, not into a separate "commons" repo. Don't move things *out* of miniforge unless there's a real benefit; do add things *into* miniforge that should be common. License posture: miniforge is Apache 2 — anything added here is OSS by default. Proprietary repos (thesium-workflows, Fleet) consume freely.

**Sharing rule:** *Share protocols and primitives; specialize schemas.* Anomaly type, response-chain accumulator, boundary wrapper, content hashing → miniforge OSS. Evidence-record *schemas* (SDLC vs RAG vs Fleet-specific) → per-repo. Miniforge keeps its `evidence-bundle` as the SDLC-shaped variant; thesium-workflows builds `inference-evidence` as the RAG-shaped variant.

**Reference rule:** Read ixi for shape and intent. Do not copy code. Reimplement under thesium-workflows' opinions (malli, colocated schemas, layer labels, `ai.thesium.*` namespace). Patterns are not copyrightable; specific implementations are.

## Workstream overview

| ID | Workstream | Repo | Blocks | Can run parallel with |
|----|------------|------|--------|------------------------|
| **H** | **Add cross-cutting components to miniforge OSS** (anomaly, response-chain, boundary, content-hash extraction) | miniforge | A, B, E | F, G |
| A | Response-chain wiring in thesium-workflows (consumes from miniforge) | thesium-workflows | C, D | F, G |
| B | Inference-evidence component (consumes miniforge primitives) | thesium-workflows | C | F, G, E |
| C | Wire chain → evidence at terminal | thesium-workflows | D | F, G, E |
| D | Semantic port from ixi (encode/recall/etc.) | thesium-workflows | — | F, G, E |
| E | Exceptions-as-data cleanup pass (uses new anomaly/response-chain components) | miniforge | — | B, C, D, F, G |
| F | Standing rule in standards pack | miniforge-standards | — | A, B, C, D, E, G, H |
| G | Test-file decomposition discipline | thesium-workflows | — | A, B, C, D, E, F, H |
| **(Fleet)** | **TBD by user — Fleet-side cleanup + consumption of new miniforge components** | miniforge-fleet | — | most |

**Recommended order:** **H lands first** (new miniforge components). Then A + F + G + E1 (inventory) in parallel. A done → B; A+B done → C; C → D. E2/E3 run in parallel after H + F2 land.

---

## Workstream H — Add cross-cutting components to miniforge OSS

**Outcome:** Miniforge OSS gains four small, domain-free components that other miniforge-family repos already-pulling-miniforge can consume directly: `anomaly`, `response-chain`, `boundary` (exception wrapper), and `content-hash` (extracted from inside `evidence-bundle`).

**Architectural framing:** Miniforge OSS is the substrate. Thesium-workflows, Fleet, and risk-dashboard already vendor it. Adding these components to miniforge — rather than spinning up a separate commons repo — keeps the dependency graph honest, avoids a new OSS repo to maintain, and makes the patterns available to the broader OSS audience.

**Discipline:** These components carry zero domain coupling. Pure data shapes, accumulators, validators, crypto helpers. Anything with a domain model attached (workflow state, retrieval result, agent intent) does NOT go here — it belongs in a domain-specific component.

### H1 — `anomaly` component

- **Repo:** miniforge
- **Path:** `components/anomaly/`
- **Namespace:** `ai.miniforge.anomaly.*`
- **Files:**
  - `src/ai/miniforge/anomaly/interface.clj`
  - `src/ai/miniforge/anomaly/contract.clj` (malli schema)
  - test files (decomposed per behavior, see test-discipline invariant)
- **Public API:**
  - `(anomaly type message data)` — constructor
  - `(anomaly? x)` — predicate
  - `Anomaly` — malli schema
  - Standard anomaly type vocabulary: `:not-found`, `:invalid-input`, `:unauthorized`, `:fault`, `:unavailable`, `:conflict`, `:timeout`, `:unsupported`, `:fatal` (mirrors cognitect anomalies)
- **Acceptance:**
  - Schema round-trip serializable (anomalies must persist into evidence records)
  - Predicate stable
  - Apache 2 license header on every file (matches miniforge convention)

### H2 — `response-chain` component

- **Repo:** miniforge
- **Path:** `components/response-chain/`
- **Namespace:** `ai.miniforge.response-chain.*`
- **Reference reading (do not modify):**
  - `engrammicai/ixi/components/responses-web/src/com/emojixi/responses_web/interface.clj` (shape)
  - `engrammicai/ixi/components/responses-web/src/com/emojixi/responses_web/core.clj` (accumulation logic)
  - `miniforge/components/evidence-bundle/src/ai/miniforge/evidence_bundle/interface.clj` (style template — license header, layer labels, requiring-resolve discipline)
- **Public API (preserve ixi semantics; modernize idioms):**
  - `(create-chain operation-key)` — start a chain
  - `(append-step chain operation-key response)` and `(append-step chain operation-key anomaly response)`
  - `(succeeded? step-or-chain)`
  - `(last-response chain)`
  - `(last-anomaly chain)`
  - `(last-successful-or chain default)`
  - `(steps chain)`
- **Data shape (validated by malli):**
  ```clojure
  {:operation keyword?
   :succeeded? boolean?
   :response-chain [{:operation keyword?
                     :succeeded? boolean?
                     :anomaly [:maybe Anomaly]
                     :response any?}]}
  ```
- **Depends on:** `anomaly` (H1)
- **Test decomposition:** Match ixi's discipline — one focused test file per behavior:
  - `interface/create_chain_test.clj`
  - `interface/append_step_test.clj`
  - `interface/anomaly_construction_test.clj`
  - `interface/succeeded_predicate_test.clj`
  - `interface/last_response_test.clj`
  - `interface/last_anomaly_test.clj`
  - `interface/malli_validation_test.clj`
  - `interface/multi_step_composition_test.clj`
- **Acceptance:**
  - All public functions validate inputs at entry
  - Layer-labeled comment headers match dependency direction
  - Apache 2 license header on every file

### H3 — `boundary` component (exception → anomaly wrapper)

- **Repo:** miniforge
- **Path:** `components/boundary/`
- **Namespace:** `ai.miniforge.boundary.*`
- **API:**
  - `(execute-with-exception-handling exception-category chain operation-key f & args)`
  - Catches `Throwable`, converts to anomaly step, appends to chain, returns chain
- **Reference:** ixi's `core/execute-with-exception-handling` (read-only)
- **Depends on:** `anomaly`, `response-chain`
- **Acceptance:**
  - Pure-data return (no rethrows except for `:fatal` category)
  - Anomaly carries throwable summary: `{:type class-name :message :cause :data}`
  - Tests cover happy path + each category, decomposed per behavior

### H4 — Extract `content-hash` from `evidence-bundle` into its own component

- **Repo:** miniforge
- **Path:** `components/content-hash/` (new)
- **Source:** `miniforge/components/evidence-bundle/src/ai/miniforge/evidence_bundle/hash.clj`
- **Approach:**
  - Move hash logic into a standalone component
  - Update `evidence-bundle` to depend on `content-hash` instead of having it inline
  - Namespace becomes `ai.miniforge.content-hash.*`
- **Public API:**
  - `(content-hash x)` — SHA-256 hex of canonical EDN of `x`
  - `(canonical-edn x)` — deterministic EDN serialization (sorted keys, stable for hashing)
- **Acceptance:**
  - Round-trip property test: equal data produces equal hashes
  - Stable across processes / JVMs
  - `evidence-bundle` builds clean against the new component
  - All `evidence-bundle` tests still pass
  - Existing miniforge consumers unaffected (no API surface change for evidence-bundle)

### H5 — Update miniforge knowledge base / README to surface the new commons

- **Repo:** miniforge
- **Files:** miniforge `readme.md`, `agents.md` (canonical agent instructions)
- **Approach:**
  - Document the four new components as the canonical primitives for cross-cutting concerns
  - Note that other miniforge-family repos consume these directly via vendoring
  - State the discipline: "If a proposed addition to miniforge carries domain coupling, it doesn't belong in `anomaly`, `response-chain`, `boundary`, or `content-hash` — make a new domain component."
- **Acceptance:**
  - Components findable from the readme component index
  - Agents can locate them without grepping

---

## Workstream A — Response-Chain Wiring (was: response-chain in thesium-workflows; now: consume from commons)

**Outcome:** thesium-workflows depends on `miniforge-clj-commons/response-chain` and `miniforge-clj-commons/boundary` via `:local/root`. The component itself is built in commons (Workstream H4/H5).

### A1 — (MOVED to Workstream H2 — component built in miniforge)

The component itself is built in miniforge OSS (see H2). Workstream A is now about **wiring thesium-workflows** to consume it.

- **Repo:** miniforge (was: thesium-workflows)
- **Path:** `components/response-chain/` (in miniforge)
- **Namespace:** `ai.miniforge.response-chain.*`
- **Reference reading:**
  - `engrammicai/ixi/components/responses-web/src/com/emojixi/responses_web/interface.clj` (shape)
  - `engrammicai/ixi/components/responses-web/src/com/emojixi/responses_web/core.clj` (accumulation logic)
  - `thesium-workflows/components/kg-store/src/ai/thesium/kg_store/interface.clj` (style template)
- **Files to create:**
  - `src/ai/thesium/response_chain/interface.clj` — public API
  - `src/ai/thesium/response_chain/contract.clj` — malli schemas for chain + step + anomaly
  - `src/ai/thesium/response_chain/core.clj` — accumulator + queries
  - `src/ai/thesium/response_chain/messages.clj` — i18n strings
  - `deps.edn`, `resources/response-chain/messages/en-US.edn`
- **Public API (preserve ixi semantics; modernize idioms):**
  - `(create-chain operation-key)` — start a chain
  - `(append-step chain operation-key response)` and `(append-step chain operation-key anomaly response)`
  - `(succeeded? step-or-chain)`
  - `(last-response chain)`
  - `(last-anomaly chain)`
  - `(last-successful-or chain default)`
  - `(steps chain)` — return the vector of steps
  - `(anomaly type message data)` — anomaly constructor
- **Data shape (validated by malli):**
  ```clojure
  {:operation keyword?
   :succeeded? boolean?
   :response-chain [{:operation keyword?
                     :succeeded? boolean?
                     :anomaly [:maybe Anomaly]
                     :response any?}]}
  ```
- **Acceptance criteria:**
  - All public functions validated via `boundary-validation/validate!` at entry
  - Component depends only on `boundary-validation`
  - Layer-labeled comment headers ("Layer 0/1/2") with matching imports
  - Apache 2 / proprietary license header on every file (match `kg-store` pattern)

### A2 — (MOVED to Workstream H3 — component built in miniforge)

- **Repo:** miniforge
- **Path:** `components/boundary/`
- **Namespace:** `ai.miniforge.boundary.*`
- **Files to create:**
  - `src/ai/thesium/response_chain/boundary.clj` — exception→anomaly converter
- **API:**
  - `(execute-with-exception-handling exception-category chain operation-key f & args)`
  - Catches `Throwable` at the boundary, converts to anomaly step, appends to chain, returns chain
- **Reference:** ixi's `core/execute-with-exception-handling` (read-only)
- **Acceptance:**
  - Pure-data return (no rethrows except for `:fatal` category)
  - Anomaly carries throwable summary: `{:type class-name :message :cause :data}`
  - Tests cover happy path + each category

### A3 — Test decomposition (set the pattern)

- **Repo:** thesium-workflows
- **Path:** `components/response-chain/test/ai/thesium/response_chain/`
- **Files to create (one focused test file per behavior):**
  - `interface/create_chain_test.clj`
  - `interface/append_step_test.clj`
  - `interface/anomaly_construction_test.clj`
  - `interface/succeeded_predicate_test.clj`
  - `interface/last_response_test.clj`
  - `interface/last_anomaly_test.clj`
  - `interface/exception_boundary_test.clj`
  - `interface/malli_validation_test.clj`
  - `interface/multi_step_composition_test.clj`
- **Reference for pattern:** `engrammicai/ixi/components/engram-memory/test/com/emojixi/engram_memory/interface/` — note the 13 focused files
- **Acceptance:** Each test file covers exactly one behavior dimension. No mixed-concern test files.

### A4 — Add anomaly-returning variant to thesium-workflows' boundary-validation

- **Repo:** thesium-workflows
- **Path:** `components/boundary-validation/src/ai/thesium/boundary_validation/`
- **Note:** thesium-workflows' `boundary-validation` stays in place (not moved into miniforge for this refactor). H-series components in miniforge use malli directly without a wrapper component, since they're small enough and miniforge may have its own validation conventions to honor. Convergence of validation discipline across repos is out of scope here.
- **Change:** Add a parallel `validate` (no `!`) that returns `{:valid? bool :explanation ...}` instead of throwing. Keep `validate!` as-is for absolute-boundary use.
- **Acceptance:**
  - Existing `validate!` callers unchanged
  - New `validate` documented as the in-flow variant
  - Tests cover both variants

---

## Workstream B — Inference-Evidence Component (thesium-workflows)

**Outcome:** A new `ai.thesium.inference-evidence` component that persists a content-hashed, queryable record of completed user interactions for audit, citation, and "why did the AI say this?" answers.

**Critical:** This is a **simpler, consumer-RAG-shaped variant** of miniforge's evidence-bundle. Do NOT copy miniforge's evidence-bundle directly — its workflow/SDLC/Terraform shape is wrong for Thesium apps.

### B1 — Scaffold inference-evidence component

- **Repo:** thesium-workflows
- **Path:** `components/inference-evidence/`
- **Reference reading:**
  - `miniforge/components/evidence-bundle/src/ai/miniforge/evidence_bundle/interface.clj` (shape, protocol pattern)
  - `miniforge/components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj` (schema discipline)
  - `miniforge/components/evidence-bundle/src/ai/miniforge/evidence_bundle/hash.clj` (content hashing — Apache 2, can copy)
- **Files to create:**
  - `src/ai/thesium/inference_evidence/interface.clj`
  - `src/ai/thesium/inference_evidence/contract.clj`
  - `src/ai/thesium/inference_evidence/schema.clj`
  - `src/ai/thesium/inference_evidence/hash.clj` (port from miniforge — Apache 2)
  - `src/ai/thesium/inference_evidence/messages.clj`
  - `src/ai/thesium/inference_evidence/datalevin_store.clj` (lazy via requiring-resolve, like kg-store)
  - `src/ai/thesium/inference_evidence/interface/protocols/inference_evidence.clj`
- **Schema (consumer-RAG-shaped, NOT SDLC-shaped):**
  ```clojure
  {:inference-evidence/id          uuid?
   :inference-evidence/session-id  uuid?       ;; per-user session
   :inference-evidence/user-id     string?     ;; identity-map source
   :inference-evidence/query       string?     ;; original user query
   :inference-evidence/created-at  inst?
   :inference-evidence/retrieval-trace
     [{:trace/chunk-id uuid?
       :trace/document-id uuid?
       :trace/score number?
       :trace/embedding-model string?}]
   :inference-evidence/synthesis-trace
     {:synthesis/chunks-used [uuid?]
      :synthesis/model string?
      :synthesis/prompt-template-version string?
      :synthesis/output-text string?}
   :inference-evidence/response-chain  ;; the rolled-up response-chain
     [...]
   :inference-evidence/content-hash string?    ;; SHA-256 of canonical EDN
   :inference-evidence/anomalies [...]}
  ```
- **Public API (mirror miniforge structure but slimmer):**
  - `(create-evidence-store opts)` — factory; lazy Datalevin via requiring-resolve
  - Protocol `InferenceEvidence`:
    - `(create-record store record)` — persist
    - `(get-record store id)`
    - `(get-record-by-session store session-id)`
    - `(query-records store criteria)` — filters: `:user-id`, `:time-range`, `:has-anomalies`
    - `(export-record store id output-path)`
- **Acceptance:**
  - Component depends only on `boundary-validation` and `response-chain`
  - Datalevin backend lazy-loaded via `requiring-resolve` (kg-store pattern)
  - Schema malli-validated at boundary

### B2 — (MOVED to Workstream H4 — content-hash extracted into its own miniforge component)

- **Repo:** miniforge (was: thesium-workflows)
- **Path:** `components/content-hash/`
- **Namespace:** `ai.miniforge.content-hash.*`
- thesium-workflows' `inference-evidence` consumes `ai.miniforge.content-hash` directly (miniforge is already vendored)

### B3 — Test decomposition

Same pattern as A3:
- `interface/create_record_test.clj`
- `interface/get_record_test.clj`
- `interface/query_by_session_test.clj`
- `interface/query_by_criteria_test.clj`
- `interface/export_record_test.clj`
- `interface/content_hash_determinism_test.clj`
- `interface/round_trip_persistence_test.clj`
- `interface/schema_validation_test.clj`
- `interface/multi_user_isolation_test.clj`

---

## Workstream C — Wire chain → evidence at terminal state

**Outcome:** Retrieval and synthesis flows in thesium-workflows accumulate a response-chain in flight; at terminal, the chain folds into an inference-evidence record and persists.

**Blocked by:** A complete, B complete.

### C1 — Folder: chain → evidence

- **Repo:** thesium-workflows
- **Path:** `components/inference-evidence/src/ai/thesium/inference_evidence/`
- **Files:** `src/ai/thesium/inference_evidence/fold.clj`
- **API:**
  - `(fold-chain->record chain context)` — returns inference-evidence map
  - `context` carries: `:user-id`, `:session-id`, `:query`, `:retrieval-trace`, `:synthesis-trace`
- **Acceptance:**
  - Pure function (no I/O)
  - Output validated against `inference-evidence/schema.clj`
  - Anomalies in the chain surface to `:inference-evidence/anomalies`

### C2 — Refactor kg-retrieval to thread response-chain

- **Repo:** thesium-workflows
- **Path:** `components/kg-retrieval/`
- **Change:**
  - Public API gains an optional `chain` parameter; if absent, creates a fresh chain
  - All branch points (vector search, fulltext, hybrid) append a step
  - On exception, route through `response-chain.boundary/execute-with-exception-handling` instead of throwing
- **Acceptance:**
  - Existing tests pass (backwards-compatible default chain creation)
  - New tests verify chain accumulates expected steps in expected order

### C3 — Wire ingest pipeline + adapter for terminal persistence

- **Repo:** thesium-workflows
- **Paths:** `components/ingest-folder/`, `components/adapter-thesium-career/`
- **Change:**
  - At each pipeline terminal, fold the chain and persist via `inference-evidence/create-record`
  - Surface persisted record id back to caller for citation linking
- **Acceptance:**
  - End-to-end test: simulated user query → record persisted → query-by-session retrieves it → content hash stable across runs

---

## Workstream D — Semantic Port from ixi

**Outcome:** ixi's encode/remember/recall/forget/restore/coalesce semantics, multi-user identity-map discipline, and entry-kind taxonomy land in thesium-workflows under modern conventions.

**Blocked by:** C complete (so semantics use the new chain/evidence pipeline).

### D1 — Entry-kind taxonomy

- **Repo:** thesium-workflows
- **New component:** `entry-taxonomy`
- **Reference:**
  - `ixi/components/engram-memory/src/com/emojixi/engram_memory/core.clj` (the `defmulti encode*` block, lines covering `:collection`, `:conversation`, `:event`, `:goal`, `:milestone`, `:note`, etc.)
- **Approach:**
  - Express kinds as malli schemas in a contract namespace (don't copy `defmulti` shape if record/protocol fits cleaner)
  - Each kind is a malli schema with required fields per ixi's shapes
- **Acceptance:**
  - All ixi kinds covered by malli schemas
  - Round-trip serialize/validate test per kind

### D2 — Memory operations component

- **Repo:** thesium-workflows
- **New component:** `memory-operations`
- **Reference:**
  - `ixi/components/engram-memory/src/com/emojixi/engram_memory/interface.clj` (operations: encode/forget/remember/recall/restore/search/coalesce)
- **Approach:**
  - Operations layered on `kg-store` protocol
  - Each operation takes/returns a response-chain (data-first)
  - Multi-user via `:user-id` in identity context, threaded explicitly through every operation
- **Acceptance:**
  - Test parity with ixi's operation tests (port the test categories, write fresh tests)
  - Operations compose: `(-> chain encode remember recall)` works without throwing

### D3 — Zettelkasten coalesce

- **Repo:** thesium-workflows
- **Path:** `components/memory-operations/src/ai/thesium/memory_operations/coalesce.clj`
- **Reference:** `ixi/components/engram-memory/src/com/emojixi/engram_memory/coalesce.clj`
- **Approach:** Reimplement the cluster-and-merge logic against `kg-store` + vector similarity from `kg-retrieval`. Preserve dry-run semantics.
- **Acceptance:**
  - Dry-run returns clusters without mutation
  - Full run merges, evidence-record captures the merge as anomalies of kind `:claim/coalesced`
  - Property-based test: coalesce is idempotent on stable input

---

## Workstream E — Exceptions-as-Data Cleanup (miniforge)

**Outcome:** `throw`/`ex-info` removed from non-boundary code paths in miniforge components. Anomaly-returning variants used at component interfaces. Boundary handlers (CLI entry, MCP entry, HTTP entry, message consumer) remain the only `try/catch` sites.

### E1 — Inventory pass

- **Repo:** miniforge
- **Output:** `work/exception-cleanup-inventory.md`
- **Approach:**
  - `rg "(throw|ex-info)" components bases --type clj` — full list
  - Categorize each hit: `:boundary`, `:fatal-only`, `:cleanup-needed`
  - Group by component for PR-sizing
- **Acceptance:**
  - Inventory file lists every hit with categorization
  - PR-sizing recommendation: components with > 20 cleanup-needed hits split into multiple PRs

### E2 — Cleanup PRs (one per component, parallel-safe)

- **Repo:** miniforge
- **Approach per component:**
  - Replace `throw (ex-info ...)` with `(response-chain/append-step chain :ns/op (response-chain/anomaly :type msg data))` (or equivalent if response-chain hasn't landed in miniforge yet — emit a plain anomaly map matching the shape)
  - Update tests to expect anomalies in return rather than thrown exceptions
  - Keep boundary-level wrappers untouched
- **Acceptance:**
  - All component tests pass
  - `bb gate` reports clean
  - No new `throw`/`ex-info` introduced

### E3 — Use the new in-tree `anomaly` and `response-chain` components

- **Repo:** miniforge
- **Approach:**
  - E2 cleanups import from the new in-tree `ai.miniforge.anomaly.interface` and `ai.miniforge.response-chain.interface` (built in H1, H2)
  - Deprecate and forward any pre-existing scattered anomaly helpers to the canonical component
- **Acceptance:**
  - Single anomaly shape across miniforge OSS components
  - No duplicate `anomaly` constructors remain in miniforge components
  - thesium-workflows + Fleet consuming the same components via vendoring

---

## Workstream F — Standing Rule in Standards Pack

**Outcome:** A standing rule that flags `throw`/`ex-info` outside designated boundary namespaces. Prevents the regression from recurring once cleared.

### F1 — Author the rule

- **Repo:** miniforge-standards (or wherever the standards pack source lives)
- **Approach:**
  - Add rule: "Exceptions only at absolute boundaries; component interfaces return anomalies."
  - Body: rationale, example violation, example correction, exception list (boundary namespaces).
  - Boundary namespace patterns to permit: `*.cli.*`, `*.boundary.*`, `*.http.*`, `*.mcp.*`, `*-main`, `execute-with-exception-handling` family
- **Acceptance:**
  - Rule lints existing miniforge clean (after E2)
  - Rule documented in standards pack index

### F2 — Lint integration

- **Repo:** miniforge-standards
- **Approach:**
  - Add a `bb review` rule that scans for `throw` / `ex-info` in `components/*/src/**/*.clj` excluding the boundary patterns
  - Output: file:line warnings with suggested anomaly-return rewrite
- **Acceptance:**
  - Runs in `bb review` workflow
  - Zero false-positives on a clean miniforge tree post-E2

### F3 — Knowledge-base entry

- **Repo:** miniforge knowledge base
- **Approach:** Surface the rule in the agent knowledge base so agent-written code doesn't reintroduce throws.

---

## Workstream G — Test-File Decomposition Discipline (thesium-workflows)

**Outcome:** Heavy components in thesium-workflows have test files decomposed by behavior dimension, matching ixi's pattern (~10–13 files per heavy component).

### G1 — Audit existing components

- **Repo:** thesium-workflows
- **Output:** `work/test-decomposition-audit.md`
- **Approach:** For each component, count test files and lines per file. Flag any single-file test that exceeds 200 lines or covers >3 behavior dimensions.
- **Acceptance:** Audit document with per-component recommendations.

### G2 — Decompose flagged components

- **Repo:** thesium-workflows
- **Approach per component:**
  - Split single test file into N focused files, one per behavior dimension
  - Naming convention: `interface/<behavior>_test.clj`
  - Reference: `ixi/components/engram-memory/test/com/emojixi/engram_memory/interface/`
- **Acceptance:**
  - Test count and coverage unchanged
  - Each new file owns exactly one behavior dimension
  - `bb test` green

### G3 — Document the convention

- **Repo:** thesium-workflows
- **File:** `docs/test-discipline.md`
- **Acceptance:** Convention documented; new components reviewed against it.

---

## Sequencing diagram

```
parallel kickoff:
  ┌─ H1..H5 (new miniforge components: anomaly, response-chain,       ┐
  │         boundary, content-hash extraction, readme update)         │
  ├─ E1 (miniforge inventory pass — independent of H)                 │
  ├─ F1+F2 (standing rule + linter — depends only on shape, not impl) │
  └─ G1 (thesium-workflows test audit)                                │
                                                                       │
H done ──┐                                                             │
         ├─→ A (thesium-workflows wiring against miniforge components) │
         │                                                             │
         ├─→ B (inference-evidence in thesium-workflows) ──┐           │
E1 done ─┤                                                 │           │
F2 done ─┤                                                 │           │
         └─→ E2/E3 (miniforge cleanup PRs) ────────────────┤           │
         └─→ Fleet cleanup (TBD by user) ─────────────────┤            │
                                                          │            │
A+B done ──→ C (wire chain→evidence at terminal) ─────────┤            │
                                                          │            │
C done ──→ D (semantic port from ixi) ────────────────────┘            │
                                                                       │
G2 → G3 (decomposition rolls forward as new components land)───────────┘
```

## Out of scope for this refactor

- Direct merge of ixi code into miniforge-ai. **Port, do not copy.**
- Changes to ixi itself. ixi is read-only reference.
- Replacing miniforge's `evidence-bundle` component. It stays as the SDLC-shaped variant for miniforge's own use.
- Renaming `boundary-validation` or its `validate!` semantics. Add `validate` alongside; don't break the existing surface.
- Multi-region / multi-tenant concerns for inference-evidence. Single-store, single-tenant for v1.

## Per-agent task template

When spawning an agent for one of the workstream items, brief it like this:

```
Repo: <repo path>
Task ID: <e.g. A1>
Goal: <one sentence>
Reference reading (do not modify):
  - <path>
Files to create or modify:
  - <path>
Public API (if applicable):
  - <signatures>
Acceptance criteria:
  - <bullet list>
Out of scope for this task:
  - <bullet list>
Coordination:
  - Blocks task X. Blocked by task Y.
```

## Invariants every agent must respect

1. **Port, do not copy** ixi code. Read for shape; reimplement under thesium-workflows opinions.
2. **Anomalies in flows, exceptions only at boundaries.** Even during the port itself.
3. **Malli at every component interface.** No spec, no informal validation.
4. **Colocated schemas.** Schemas live with the component that owns them, not in shared schema components.
5. **Layer-labeled imports.** Comment headers must match dependency direction.
6. **License header on every file.** Match the `kg-store` pattern.
7. **Test decomposition.** New components ship with behavior-decomposed test files from day one.
8. **Worktree per workstream.** Never work on `main` directly.
9. **Don't move things OUT of miniforge.** Miniforge is OSS, already vendored by every other repo. Adding cross-cutting components to miniforge is the right move. Extracting components out of miniforge into a new repo creates a second OSS repo to maintain for no real benefit.
10. **Domain-free goes in miniforge OSS commons; domain-specific goes per-repo.** The anomaly shape, response-chain accumulator, content-hash logic are domain-free → miniforge OSS components. Evidence-record schemas (SDLC vs RAG vs Fleet-specific), retrieval shapes, agent intents are domain-specific → per-repo components.
11. **Single anomaly shape across all repos.** Once H1 lands, no local anomaly helpers — every miniforge-family repo imports `ai.miniforge.anomaly` from miniforge.
12. **Apache 2 by default for new miniforge components.** They become available to the broader OSS audience automatically. Only proprietary content goes into proprietary repos.
