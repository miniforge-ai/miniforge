<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 Delta 3 — Observational Entities for Evidence, Artifact, Task, Decision, Pack Views

- **Spec ID:** `N5-delta-observational-entities-v1`
- **Version:** `0.1.0-draft`
- **Status:** Draft
- **Date:** 2026-04-21
- **Amends:** N5 — Interface Standard: CLI/TUI/API (§3.2.3 Evidence Viewer, §3.2.4 Artifact Browser,
  §3.2.5 DAG Kanban, §3.2.7 Listener/Control Panel, §3.2.11 Pack Browser)
- **Amends:** N5-delta-supervisory-control-plane-v1 (§3.1 v1 entities, §3.4 supervisory-state component)
- **Related:** N3 (event stream), N4 (policy packs), N6 (evidence & provenance), N9 (external PR integration)

## 1. Purpose

N5-delta-1 (supervisory control plane) defined five canonical entities (`WorkflowRun`, `AgentSession`,
`PrFleetEntry`, `PolicyEvaluation`, `AttentionItem`) and a projection component (`supervisory-state`)
that materializes them for external renderers. That coverage gets the TUI's four primary zones —
Workflows, Agents, PR Fleet, Attention — and their existing drill-downs.

N5 §3.2 specifies **seven additional TUI views** that the current entity set cannot back. N5-delta-2
(PR scoring) closed two (§3.2.8 / §3.2.9). This delta closes the remaining five observational views
by introducing five new canonical entities and their associated events. Interactive views (§3.2.12
Run Launcher) and governance-action views (Waiver UX) are out of scope here — they require input
affordances and are deferred to a later delta.

### 1.1 What this delta adds

| Entity            | Backs N5 §                | Kind                          |
|-------------------|---------------------------|-------------------------------|
| `EvidenceBundle`  | §3.2.3 Evidence Viewer    | Summary + drill-down record   |
| `ArtifactMetadata`| §3.2.4 Artifact Browser   | Metadata only (content on demand) |
| `TaskNode`        | §3.2.5 DAG Kanban         | Projected DAG node state      |
| `DecisionCard`    | §3.2.7 Decision Queue     | Pending/resolved agent request|
| `PackManifest`    | §3.2.11 Pack Browser      | Installed/available pack      |

All five are added to N5-delta-1 §3.1 as required v1-renderable entities. Existing consumers
continue to work under the open-schema rule (additional top-level collections on
`SupervisoryState` pass through unknown-collection-agnostic readers).

### 1.2 What this delta does NOT change

- **N5-delta-1 §3.4 invariant 6** — `supervisory-state` remains the sole emitter of
  `:supervisory/*-upserted` events for all five new entities. Fine-grained sources keep emitting
  their own lifecycle events (e.g. `:task/state-changed`, `:control-plane/decision-created`);
  this delta only specifies how `supervisory-state` rolls them up.
- **Bundle storage semantics** — evidence bundles and artifact content continue to live in their
  respective stores (N6 evidence bundle / artifact store). This delta adds snapshot events that
  carry display-facing shapes; drill-down to full content uses the existing store APIs.
- **N4 pack trust semantics** — the `:pack/trust-level` field carried here mirrors the current
  knowledge-promotion record. This delta does not change how trust is computed.

## 2. Entity shapes

All five are added to the N5-delta-1 §3.1 v1 set. Open-map rule applies: producers MAY include
additional fields; consumers MUST preserve unknowns for round-trip.

### 2.1 `EvidenceBundle`

Projection of the N6 evidence bundle for `supervisory-state`. The full bundle stays in the
evidence store; this record is the display surface for N5 §3.2.3. The bundle contains every
required N6 §2 section in summarized form — consumers can drill into the store for full plan
transcripts, reviewer notes, etc.

```clojure
[:map {:registry registry}
 [:evidence/id :id/uuid]
 [:evidence/workflow-run-id :id/uuid]
 [:evidence/intent
   [:map
    [:intent/type keyword?]                      ; matches N6 §2.1
    [:intent/description string?]
    [:intent/business-reason {:optional true} [:maybe string?]]
    [:intent/constraints {:optional true} [:vector string?]]]]
 [:evidence/phase-summaries
   [:vector [:map
             [:phase keyword?]                   ; :plan | :implement | :verify | :review
             [:phase/agent string?]
             [:phase/status keyword?]            ; :success | :failed | :skipped
             [:phase/duration-ms :common/non-neg-int]
             [:phase/inner-loop-iterations {:optional true} :common/non-neg-int]
             [:phase/artifact-ids {:optional true} [:vector :id/uuid]]]]]
 [:evidence/semantic-validation
   [:map
    [:passed? boolean?]
    [:declared-intent {:optional true} string?]
    [:actual-behavior {:optional true} string?]]]
 [:evidence/policy-summary
   [:map
    [:packs-applied [:vector string?]]
    [:overall-passed? boolean?]
    [:violation-count :common/non-neg-int]]]
 [:evidence/outcome
   [:map
    [:outcome/success boolean?]
    [:outcome/summary string?]
    [:outcome/workflow-status keyword?]]]         ; :completed | :failed | :cancelled
 [:evidence/derived-at :common/timestamp]]
```

### 2.2 `ArtifactMetadata`

Display surface for N5 §3.2.4. Content deliberately excluded — content/stdout/bytes may exceed a
megabyte; supervisory state remains lightweight. The UI drills to `artifact-store/get-content`
(existing protocol) on demand.

```clojure
[:map {:registry registry}
 [:artifact/id :id/uuid]
 [:artifact/type keyword?]                        ; :plan-document | :code-changes | …
 [:artifact/phase {:optional true} [:maybe keyword?]]
 [:artifact/workflow-run-id {:optional true} [:maybe :id/uuid]]
 [:artifact/size-bytes {:optional true} [:maybe :common/non-neg-int]]
 [:artifact/content-hash {:optional true} [:maybe string?]]
 [:artifact/content-type {:optional true} [:maybe string?]]  ; e.g. "text/markdown"
 [:artifact/created-at :common/timestamp]
 [:artifact/origin {:optional true} [:maybe string?]]        ; component that wrote it
 [:artifact/parents {:optional true} [:vector :id/uuid]]     ; provenance chain
 [:artifact/children {:optional true} [:vector :id/uuid]]
 [:artifact/provenance {:optional true}
   [:map
    [:provenance/executor-type {:optional true} [:maybe keyword?]]
    [:provenance/image-digest {:optional true} [:maybe string?]]
    [:provenance/duration-ms {:optional true} [:maybe :common/non-neg-int]]
    [:provenance/exit-code {:optional true} [:maybe :common/non-neg-int]]
    [:provenance/environment-id {:optional true} [:maybe string?]]]]]
```

### 2.3 `TaskNode`

Projection of one DAG node, as observed by `supervisory-state` via
`:task/state-changed` events. The Kanban column assignment is OPTIONAL on the wire — consumers
MAY re-derive from `:task/status` + dependency resolution — but `supervisory-state` SHOULD emit
it pre-computed so consumers are consistent.

```clojure
[:map {:registry registry}
 [:task/id :id/uuid]
 [:task/workflow-run-id :id/uuid]
 [:task/description string?]
 [:task/type {:optional true} [:maybe keyword?]]   ; :implement :test :review …
 [:task/component {:optional true} [:maybe string?]]
 [:task/status :task/status]                       ; enum below
 [:task/kanban-column :task/kanban-column]         ; derived; enum below
 [:task/dependencies {:optional true} [:vector :id/uuid]]
 [:task/dependents {:optional true} [:vector :id/uuid]]
 [:task/started-at {:optional true} [:maybe :common/timestamp]]
 [:task/completed-at {:optional true} [:maybe :common/timestamp]]
 [:task/elapsed-ms {:optional true} [:maybe :common/non-neg-int]]
 [:task/exclusive-files? {:optional true} boolean?]
 [:task/stratum? {:optional true} boolean?]]
```

Enums:

- `:task/status` ∈ { `:pending`, `:ready`, `:running`, `:ci-running`, `:review-pending`,
  `:ready-to-merge`, `:merging`, `:merged`, `:completed`, `:failed`, `:skipped`, `:cancelled` }
- `:task/kanban-column` ∈ { `:blocked`, `:ready`, `:active`, `:in-review`, `:merging`, `:done` }

Derivation from status to Kanban column (N5 §3.2.5):

| Status                         | Kanban column |
|--------------------------------|---------------|
| `:pending` (deps unresolved)   | `:blocked`    |
| `:pending` (deps resolved) / `:ready` | `:ready` |
| `:running` / `:ci-running`     | `:active`     |
| `:review-pending`              | `:in-review`  |
| `:ready-to-merge` / `:merging` | `:merging`    |
| `:merged` / `:completed` / `:failed` / `:skipped` / `:cancelled` | `:done` |

### 2.4 `DecisionCard`

Display projection of `control-plane/decision-queue` records (existing). This delta does not
introduce a new queue — it specifies how the queue state reaches `supervisory-state` and the TUI.

```clojure
[:map {:registry registry}
 [:decision/id :id/uuid]
 [:decision/agent-id :id/uuid]
 [:decision/workflow-run-id {:optional true} [:maybe :id/uuid]]
 [:decision/type :decision/type]                  ; :approval | :choice | :input | :confirmation
 [:decision/priority :decision/priority]          ; :critical | :high | :medium | :low
 [:decision/status :decision/status]              ; :pending | :resolved | :expired
 [:decision/summary string?]
 [:decision/context {:optional true} [:maybe string?]]
 [:decision/options {:optional true} [:vector string?]]
 [:decision/deadline {:optional true} [:maybe :common/timestamp]]
 [:decision/created-at :common/timestamp]
 [:decision/resolution {:optional true} [:maybe string?]]
 [:decision/comment {:optional true} [:maybe string?]]
 [:decision/resolved-at {:optional true} [:maybe :common/timestamp]]]
```

### 2.5 `PackManifest`

Display projection of an installed pack (N4 §2.2) plus its current local trust state. Applies to
policy packs, workflow packs, and knowledge packs.

```clojure
[:map {:registry registry}
 [:pack/id keyword?]                               ; :miniforge/core
 [:pack/version string?]                           ; semver
 [:pack/title string?]
 [:pack/type :pack/type]                           ; :policy | :workflow | :knowledge
 [:pack/description {:optional true} [:maybe string?]]
 [:pack/author {:optional true} [:maybe string?]]
 [:pack/license {:optional true} [:maybe string?]]
 [:pack/trust-level :pack/trust-level]             ; :untrusted | :tainted | :trusted
 [:pack/installed? boolean?]
 [:pack/update-available? {:optional true} boolean?]
 [:pack/source {:optional true} [:maybe string?]]  ; registry URL or local path
 [:pack/pack-hash {:optional true} [:maybe string?]]
 [:pack/signature? {:optional true} boolean?]
 [:pack/capabilities-requested {:optional true} [:vector string?]]
 [:pack/tags {:optional true} [:vector string?]]
 [:pack/target-types {:optional true} [:vector keyword?]]
 [:pack/installed-at {:optional true} [:maybe :common/timestamp]]]
```

## 3. Producer contract

All five entities MUST be materialized by `components/supervisory-state` (N5-δ1 §3.4). This
preserves invariant 6 — only the supervisory projection component emits snapshot events — and
keeps all TUI consumers on a single source of truth.

### 3.1 EvidenceBundle producer

- **Consumes:** `:workflow/completed`, `:workflow/failed`, `:workflow/cancelled`, and any
  evidence-mutating event emitted by `components/evidence-bundle` (to be enumerated when the
  bundle component exports a finer event taxonomy).
- **Behavior:** On workflow-terminal events, `supervisory-state` reads the just-assembled bundle
  from the evidence store and projects it into the §2.1 shape. The full bundle remains in the
  store; this snapshot carries only the summary.
- **Emits:** `:supervisory/evidence-upserted` per §4.1.

### 3.2 ArtifactMetadata producer

- **Consumes:** write signals from the artifact store (`:artifact/stored`, or an equivalent emit
  hook added when `components/artifact` exports it — current stores are protocol-based and do
  not emit events; this delta assumes the hook is added as a prerequisite).
- **Behavior:** On each artifact write, `supervisory-state` projects metadata (NOT content) into
  the §2.2 shape. Provenance chain is captured at write time by the executor and included
  verbatim.
- **Emits:** `:supervisory/artifact-upserted` per §4.2.

### 3.3 TaskNode producer

- **Consumes:** `:task/state-changed`, `:dag/task-completed`, `:dag/task-skip-propagated`.
- **Behavior:** Maintains per-`:workflow-run-id` task tables; re-derives `:task/kanban-column`
  on every mutation using the table in §2.3. Unmet-dependency detection SHOULD be done against
  the component's own task table, not by re-querying the orchestrator.
- **Emits:** `:supervisory/task-node-upserted` per §4.3.

### 3.4 DecisionCard producer

- **Consumes:** `:control-plane/decision-created`, `:control-plane/decision-resolved`,
  `:control-plane/decision-expired` (when the control-plane starts emitting it; until then,
  supervisory-state MAY derive `:expired` status from `:decision/deadline` vs. wall-clock as
  a last resort).
- **Behavior:** Mirrors the `control-plane/decision-queue` state into the §2.4 shape. Resolution
  and comment are populated on resolve events.
- **Emits:** `:supervisory/decision-upserted` per §4.4.

### 3.5 PackManifest producer

- **Consumes:** an install/update/promote signal from the pack loader (`:pack/installed`,
  `:pack/updated`, `:pack/trust-promoted`, `:pack/uninstalled` — to be exposed by
  `components/knowledge` as a prerequisite; current state is protocol-calls-only).
- **Behavior:** One entry per installed pack; trust level reflects the current local decision
  (promoted via evidence per N4 / knowledge-promotion). Not-installed packs (available in a
  registry) MAY be projected with `:pack/installed? = false` for discovery display.
- **Emits:** `:supervisory/pack-manifest-upserted` per §4.5.

### 3.6 Shared contract

All five producers MUST respect the N5-δ1 §3.4 coalescing window (≤ 100 ms per entity) so high-
frequency sources (especially `:task/state-changed` in wide DAGs) do not flood the event stream.
Idempotency rule from N5-δ2 §3.4 applies — re-emitting an upsert with unchanged fields MUST
produce byte-identical payloads so downstream diff detection is stable.

## 4. Event-stream additions

All five events follow the N3 §2.1 envelope. Each `:supervisory/entity` field carries the entity
shape from §2.

### 4.1 `:supervisory/evidence-upserted`

```clojure
{:event/type           :supervisory/evidence-upserted
 :event/id             #uuid
 :event/timestamp      #inst
 :event/version        "1.0.0"
 :event/sequence-number long
 :workflow/id          #uuid     ; convenience — same as :evidence/workflow-run-id
 :message              string
 :supervisory/entity   {… §2.1 shape …}}
```

### 4.2 `:supervisory/artifact-upserted`

```clojure
{:event/type         :supervisory/artifact-upserted
 … envelope …
 :supervisory/entity {… §2.2 shape …}}
```

### 4.3 `:supervisory/task-node-upserted`

```clojure
{:event/type         :supervisory/task-node-upserted
 … envelope …
 :workflow/id        #uuid     ; :task/workflow-run-id
 :supervisory/entity {… §2.3 shape …}}
```

### 4.4 `:supervisory/decision-upserted`

```clojure
{:event/type         :supervisory/decision-upserted
 … envelope …
 :supervisory/entity {… §2.4 shape …}}
```

### 4.5 `:supervisory/pack-manifest-upserted`

```clojure
{:event/type         :supervisory/pack-manifest-upserted
 … envelope …
 :supervisory/entity {… §2.5 shape …}}
```

## 5. TUI rendering — normative surfaces

### 5.1 Evidence Viewer (N5 §3.2.3)

The panel MUST render the five top-level sections of §2.1 in fixed order — intent, phases,
semantic-validation, policy-summary, outcome. Each phase row SHOULD show agent, status, duration,
inner-loop iteration count, and an artifact-count link to the Artifact Browser filtered by
those IDs. Outcome rows MUST colour success/failure per the status palette.

### 5.2 Artifact Browser (N5 §3.2.4)

Rows MUST show: id, type, phase, size, created-at, origin. A provenance column SHOULD render a
compact representation of the parent chain (e.g. `← 2 parents`). Drill-in loads full content via
`artifact-store/get-content` and renders by content-type (markdown highlighted, JSON pretty-
printed, binary shown as hex preview).

### 5.3 DAG Kanban (N5 §3.2.5)

Six columns in this order: BLOCKED, READY, ACTIVE, IN-REVIEW, MERGING, DONE. Column assignment
MUST use `:task/kanban-column` verbatim. Each card MUST show task description (truncated),
status tag, elapsed time for non-terminal tasks, and a dependency-arrow indicator when
`:task/dependencies` is non-empty. Failed tasks in the DONE column MUST be coloured
`status_failed` per the existing palette.

### 5.4 Decision Queue (N5 §3.2.7)

Pending decisions ordered by priority (`:critical` first) then `:created-at` ascending. Each
card shows agent-id (with link to Agent detail), type, priority, summary, and deadline countdown
when `:decision/deadline` is set. Resolved decisions MAY be shown in a collapsed-by-default
"recent" section with resolution + comment.

### 5.5 Pack Browser (N5 §3.2.11)

Columns: id, version, type, trust-level, installed?, update-available?. Trust level MUST map to
the palette: `:trusted → status_ok`, `:tainted → status_warning`, `:untrusted → status_failed`.
Install / update / remove affordances are out of scope for this delta (they belong with the Run
Launcher / Pack Management UX in a future delta).

## 6. Rust consumer contract

The `supervisory-entities` crate (`miniforge-control/contracts/crates/supervisory-entities`) MUST
grow one struct per §2 entity with a serde derive that round-trips with the Clojure wire format
as exhibited by N5-δ1 and N5-δ2. `SupervisoryState` gains five new `HashMap` collections keyed
by entity id (UUID for the four id-bearing entities; `:pack/id` keyword for packs), and five
matching apply helpers. Each upsert event MUST be handled by the Rust consumer's
`StateManager` in the same match-on-`EventType` style established by N5-δ1 / N5-δ2.

Rust consumers MUST NOT derive `:task/kanban-column` locally — the producer's value is
authoritative per §3.3, and consumer-side derivation would diverge on Kanban policy changes.

## 7. Backwards compatibility and rollout

1. **Additive only.** No existing entity shape changes. Pre-delta consumers ignore new entity
   collections under the open-schema rule.
2. **Per-entity rollout.** The five entities are independent; producers MAY ship in any order.
   A consumer that recognizes three of five snapshot events renders those three views and
   leaves the other two showing a "not yet available" placeholder.
3. **No historical backfill.** Snapshots materialize forward from deployment. The TUI MUST
   tolerate mixed fleets (e.g. old workflow runs with no `EvidenceBundle` entry) by showing
   the empty-state placeholder per each §5 section.
4. **Deferred prerequisites.** Sections §3.1 (evidence-bundle events), §3.2 (artifact-store
   events), §3.5 (pack-loader events) each require a small producer-side hook not in the
   current code. Those hooks SHOULD be added in separate feature PRs ahead of the
   supervisory-state wiring for each entity. Consumers can ship against the event schemas
   defined here before the hooks land — they simply render empty state until the hooks emit.

## 8. Acceptance criteria

For each entity independently, the delta is satisfied when:

- [ ] The Clojure schema exists in `components/supervisory-state/schema.clj` matching §2
- [ ] The `supervisory-state` accumulator handles the relevant fine-grained events and the
      corresponding `:supervisory/*-upserted` event is emitted
- [ ] `components/pr-scoring`'s single-emitter invariant analogue holds: no other component
      emits the snapshot events defined in §4
- [ ] The Rust `supervisory-entities` crate gains the mirror struct with serde round-trip
      tests (absent-field and full-field cases)
- [ ] The `SupervisoryState` gains its new collection + apply helper, and `StateManager`
      dispatches the snapshot event
- [ ] The Rust TUI renders the mapped N5 §3.2.x view per §5 for that entity
- [ ] Empty-state rendering works for mixed fleets where the producer has not yet emitted
      a snapshot

The delta is fully satisfied when all five entities meet the per-entity checklist.
