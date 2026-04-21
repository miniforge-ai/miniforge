<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 Delta 3 — Evidence, Artifact, Task, Decision, and Pack Entities + Pack Management

- **Spec ID:** `N5-delta-evidence-artifact-task-decision-pack-v1`
- **Version:** `0.2.0-draft`
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
(PR scoring) closed two (§3.2.8 / §3.2.9). This delta closes five of the remaining views by adding
five canonical entities, their associated events, and the **pack-management actions** that operate
on `PackManifest`. The Run Launcher (§3.2.12) and Waiver UX remain out of scope — those require
larger input-form patterns and are deferred to a later delta.

### 1.1 What this delta adds

| Entity / Capability | Backs N5 §               | Kind                          |
|---------------------|--------------------------|-------------------------------|
| `EvidenceBundle`    | §3.2.3 Evidence Viewer   | Full bundle per N6 (open map) |
| `ArtifactMetadata`  | §3.2.4 Artifact Browser  | Metadata only (content on demand) |
| `TaskNode`          | §3.2.5 DAG Kanban        | Projected DAG node state      |
| `DecisionCard`      | §3.2.7 Decision Queue    | Pending/resolved agent request|
| `PackManifest`      | §3.2.11 Pack Browser     | Installed + available pack    |
| Pack CRUD + compile | §3.2.11 Pack Browser     | Install / update / uninstall / trust-promote / compile-from-doc |

All five entities are added to N5-delta-1 §3.1 as required v1-renderable entities. Pack-management
actions are specified here because they operate on `PackManifest` and share its event envelope;
they are the one intentionally interactive surface in this delta. Existing consumers continue to
work under the open-schema rule (additional top-level collections on `SupervisoryState` pass
through unknown-collection-agnostic readers).

### 1.2 What this delta does NOT change

- **N5-delta-1 §3.4 invariant 6** — `supervisory-state` remains the sole emitter of
  `:supervisory/*-upserted` events for all five new entities. Fine-grained sources keep emitting
  their own lifecycle events (e.g. `:task/state-changed`, `:control-plane/decision-created`);
  this delta only specifies how `supervisory-state` rolls them up.
- **Artifact storage semantics** — artifact content continues to live in the artifact store.
  This delta adds the metadata snapshot; drill-down to full content uses the existing store API.
- **N4 pack trust semantics** — the `:pack/trust-level` field carried here mirrors the current
  knowledge-promotion record. This delta does not change how trust is computed, only how
  promotion events surface to `supervisory-state`.
- **Open-map convention** — per the project-wide schema posture, all entities in §2 are open
  maps. Consumers care about the fields they care about; unknown keys pass through untouched.
  Where this delta names enums (e.g. `:pack/trust-level`), the named values are a normative
  *known set* — producers MAY emit additional values and consumers MUST preserve them for
  round-trip. Closed enumerations are explicitly called out where they exist (§2.3 Kanban
  columns).

## 2. Entity shapes

All five are added to the N5-delta-1 §3.1 v1 set. Open-map rule applies: producers MAY include
additional fields; consumers MUST preserve unknowns for round-trip.

### 2.1 `EvidenceBundle`

Full N6 evidence bundle carried verbatim on the supervisory projection. Consumers (TUI,
native console) render whichever fields they need; additional N6 fields the store assembles
(or that future bundle versions add) pass through unmodified per the open-map rule.

Required top-level keys — MUST be present on every bundle:

```clojure
[:map {:registry registry, :closed false}
 [:evidence/id :id/uuid]
 [:evidence/workflow-run-id :id/uuid]
 [:evidence/intent            :map]  ; N6 §2.1 — full intent record
 [:evidence/plan              :map]  ; N6 §2.2 — plan-phase evidence (inputs, decisions, artifacts)
 [:evidence/implement         :map]  ; N6 §2.3 — implement-phase evidence
 [:evidence/verify            :map]  ; N6 §2.4 — verify-phase evidence incl. inner-loop iterations
 [:evidence/review            :map]  ; N6 §2.5 — review-phase evidence
 [:evidence/semantic-validation :map] ; N6 §2.6 — declared vs. actual behaviour
 [:evidence/policy-checks     [:vector :map]] ; per-pack evaluation records
 [:evidence/outcome           :map]  ; N6 §2.7 — final outcome summary
 [:evidence/derived-at        :common/timestamp]]
```

The inner shapes are deliberately left as `:map` with `:closed false` semantics: the full
schema is whatever `components/evidence-bundle/schema.clj` produces today, and bundle producers
MAY add further sub-keys (per-tool traces, reviewer transcripts, extended provenance) without
needing a spec bump. Consumers MUST preserve unknown keys on serialization round-trips.

Where a consumer renders a summary (e.g. a Kanban card footer), it SHOULD read the subset it
needs directly from the bundle rather than requesting a different event shape. This keeps the
producer contract single-event and avoids a supervisory-summary/supervisory-full split.

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
[:map {:registry registry, :closed false}
 [:task/id :id/uuid]
 [:task/workflow-run-id :id/uuid]
 [:task/description string?]
 [:task/type {:optional true} [:maybe keyword?]]
 [:task/component {:optional true} [:maybe string?]]
 [:task/status keyword?]                           ; open; known set below
 [:task/kanban-column :task/kanban-column]         ; closed enum — display contract
 [:task/dependencies {:optional true} [:vector :id/uuid]]
 [:task/dependents {:optional true} [:vector :id/uuid]]
 [:task/started-at {:optional true} [:maybe :common/timestamp]]
 [:task/completed-at {:optional true} [:maybe :common/timestamp]]
 [:task/elapsed-ms {:optional true} [:maybe :common/non-neg-int]]
 [:task/exclusive-files? {:optional true} boolean?]
 [:task/stratum? {:optional true} boolean?]]
```

`:task/status` is an **open** keyword — the DAG state-profile system
(`components/dag-executor/resources/config/dag-executor/state-profiles/*.edn`) lets each
workflow family declare its own status vocabulary, and new profiles can add statuses without
requiring a spec revision. Known values at time of writing:

- `:pending`, `:ready`, `:running`, `:completed`, `:failed`, `:skipped`, `:cancelled` (kernel
  profile — universal)
- `:ci-running`, `:review-pending`, `:ready-to-merge`, `:merging`, `:merged` (software-factory
  profile)

Producers MAY emit additional keywords; consumers MUST preserve them and MUST fall back to the
`:blocked` Kanban column when the mapping below has no entry (see below).

`:task/kanban-column` is **closed** — the six columns are the display contract for N5 §3.2.5
and changes to the set would break consumers. Column values: `:blocked`, `:ready`, `:active`,
`:in-review`, `:merging`, `:done`.

Status → column mapping (the producer SHOULD emit the column pre-derived; consumers that need
to derive locally MUST use this table exactly):

| Status                         | Kanban column |
|--------------------------------|---------------|
| `:pending` (deps unresolved)   | `:blocked`    |
| `:pending` (deps resolved) / `:ready` | `:ready` |
| `:running` / `:ci-running`     | `:active`     |
| `:review-pending`              | `:in-review`  |
| `:ready-to-merge` / `:merging` | `:merging`    |
| `:merged` / `:completed` / `:failed` / `:skipped` / `:cancelled` | `:done` |
| any other status               | `:blocked` (safe default — unknown state MUST surface visibly) |

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

Projection of a pack (N4 §2.2) visible to this node — either installed locally or available in a
registry the node knows about. Applies to policy packs, workflow packs, and knowledge packs.

```clojure
[:map {:registry registry, :closed false}
 [:pack/id keyword?]                               ; :miniforge/core
 [:pack/version string?]                           ; semver
 [:pack/title string?]
 [:pack/type keyword?]                             ; known: :policy | :workflow | :knowledge
 [:pack/description {:optional true} [:maybe string?]]
 [:pack/author {:optional true} [:maybe string?]]
 [:pack/license {:optional true} [:maybe string?]]
 [:pack/trust-level keyword?]                      ; known: :untrusted | :tainted | :trusted
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

Supervisory-state carries both installed and registry-available packs. The `installed?` flag
lets the Pack Browser distinguish discovery ("what could I install?") from inventory ("what's
already here?"). Fleet mode (N5-δ1 §3.3) aggregates per-node PackManifests into a fleet-wide
view — the same entity shape scales from single-node to fleet.

### 2.6 Pack-management intents

Pack-management affordances (§3.5, §4.6, §5.6) operate on `PackManifest` entries and carry
enough context to round-trip through evidence. Intent payloads on the wire:

```clojure
;; :pack/install
{:pack-install/pack-id         keyword
 :pack-install/version         string                 ; semver or ":latest"
 :pack-install/source          string                 ; registry URL or file path
 :pack-install/requested-trust keyword                ; :untrusted (default) | :trusted
 :pack-install/capabilities    [string]               ; capabilities the installer grants}

;; :pack/update
{:pack-update/pack-id   keyword
 :pack-update/to-version string}

;; :pack/uninstall
{:pack-uninstall/pack-id keyword}

;; :pack/trust-promote  (delegates to the existing knowledge-promotion record)
{:pack-trust/pack-id    keyword
 :pack-trust/to-level   keyword                       ; :trusted | :tainted | :untrusted
 :pack-trust/justification string                     ; required for :trusted promotions}

;; :pack/compile-from-doc — turn a Markdown (or other) document into a policy pack per N4
{:pack-compile/source-path     string                 ; path to source document(s)
 :pack-compile/pack-id         keyword                ; output pack id
 :pack-compile/pack-version    string
 :pack-compile/target-types    [keyword]              ; :terraform, :kubernetes, …
 :pack-compile/install?        boolean                ; install immediately after compile
 :pack-compile/requested-trust keyword}
```

All five intent shapes are open maps; additional provenance keys MAY be attached (e.g.
`:pack-install/installer-agent-id` for audit).

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

- **Consumes:** lifecycle signals from the pack loader + registry client (`:pack/installed`,
  `:pack/updated`, `:pack/uninstalled`, `:pack/trust-promoted`, `:pack/compiled`, and a
  registry-refresh signal for available packs — to be exposed by `components/knowledge` and
  `components/pack-registry` as prerequisites; current state is protocol-calls-only).
- **Behavior:** One entry per (pack-id, version) pair visible to the node. Installed packs
  carry the locally-computed trust level; registry-available packs carry
  `:pack/installed? = false` and inherit the registry's published trust hint (which is
  advisory — the installer resolves the local trust on install per N4).
- **Emits:** `:supervisory/pack-manifest-upserted` per §4.5.

### 3.6 Pack-management producer (new operational component)

Pack CRUD and doc-compilation are executed by a dedicated component, `components/pack-ops`
(new), which:

1. Accepts intent events (§2.6 shapes) published by the TUI / CLI / API.
2. Dispatches to the appropriate handler: pack-loader (`install`/`update`/`uninstall`),
   knowledge-promotion (`trust-promote`), or the doc-to-policy compiler (`compile-from-doc`).
3. Emits an outcome event for each intent: `:pack/install-completed`, `:pack/install-failed`,
   `:pack/update-completed`, etc. (see §4.6). Every outcome MUST carry the originating
   intent id so requesters can correlate.
4. Produces or refreshes `PackManifest` rows as side effects. These surface in the next
   `:supervisory/pack-manifest-upserted` emission (no direct snapshot emission from
   `pack-ops` — invariant 6 still holds).
5. Writes audit evidence per N6 for each operation: who requested, when, which pack, trust
   promotion justification (if any), and compiler inputs+outputs for `compile-from-doc`.

**Authorization:** intent handlers MUST check the requester's capability grants (per N5-δ1 §7
intervention vocabulary). `:trusted`-level trust promotions and installs of packs that request
capabilities beyond the requester's own grant MUST be rejected with an outcome event of
`:pack/*-failed` and a reason code.

**Doc-compilation contract:** `compile-from-doc` accepts a directory of prose documents
(Markdown by default), extracts rule-like assertions per N4 §4 compilation contract, and emits
a compiled pack under `:pack-compile/pack-id`. Compilation MUST be deterministic — the same
inputs MUST produce the same pack hash — and MUST preserve a provenance link from the output
pack to the source documents.

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

### 4.6 Pack-management intent + outcome events

Intents are published by the TUI / CLI / API; outcomes are published by `components/pack-ops`.
Both follow the N3 §2.1 envelope.

| Event type                        | Direction | Payload shape    |
|-----------------------------------|-----------|------------------|
| `:pack/install-requested`         | → ops     | §2.6 `:pack/install`         |
| `:pack/install-completed`         | ← ops     | `{:pack/id, :pack/version, :intent-id, :installed-at}` |
| `:pack/install-failed`            | ← ops     | `{:pack/id, :intent-id, :reason, :reason-code}`        |
| `:pack/update-requested`          | → ops     | §2.6 `:pack/update`          |
| `:pack/update-completed`          | ← ops     | `{:pack/id, :pack/version, :intent-id}`                |
| `:pack/update-failed`             | ← ops     | `{:pack/id, :intent-id, :reason, :reason-code}`        |
| `:pack/uninstall-requested`       | → ops     | §2.6 `:pack/uninstall`       |
| `:pack/uninstall-completed`       | ← ops     | `{:pack/id, :intent-id}`                               |
| `:pack/uninstall-failed`          | ← ops     | `{:pack/id, :intent-id, :reason, :reason-code}`        |
| `:pack/trust-promote-requested`   | → ops     | §2.6 `:pack/trust-promote`   |
| `:pack/trust-promote-completed`   | ← ops     | `{:pack/id, :to-level, :intent-id}`                    |
| `:pack/trust-promote-failed`      | ← ops     | `{:pack/id, :intent-id, :reason, :reason-code}`        |
| `:pack/compile-requested`         | → ops     | §2.6 `:pack/compile-from-doc`|
| `:pack/compile-completed`         | ← ops     | `{:pack/id, :pack/version, :pack-hash, :intent-id, :source-hash}` |
| `:pack/compile-failed`            | ← ops     | `{:intent-id, :reason, :reason-code}`                  |

Every intent event MUST carry a client-generated `:intent/id` (uuid) so the requesting UI can
correlate the matching `*-completed` / `*-failed` outcome. `:reason-code` is a keyword (e.g.
`:unauthorized`, `:network-error`, `:compile-error`, `:conflict`); `:reason` is a free-form
operator-facing string.

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
Installed and available packs MAY be shown in the same list (with a visual distinction) or in
two tabs — consumer's choice; both presentations are compatible with the shape in §2.5.

### 5.6 Pack-management actions

The Pack Browser MUST expose the following actions on a selected pack row. Each action publishes
the matching `:pack/*-requested` event from §4.6 and renders the lifecycle state machine below
as transient UI feedback (progress spinner / toast on completion / error banner on failure).

| Action              | Intent event                    | Enabled when                               |
|---------------------|---------------------------------|--------------------------------------------|
| Install             | `:pack/install-requested`       | `installed? = false`                       |
| Update              | `:pack/update-requested`        | `installed? = true AND update-available? = true` |
| Uninstall           | `:pack/uninstall-requested`     | `installed? = true`                        |
| Promote trust       | `:pack/trust-promote-requested` | `installed? = true`                        |
| Compile from doc    | `:pack/compile-requested`       | always available (input is user-supplied path) |

**State machine per pending intent:**

1. User action → renderer publishes `*-requested` with fresh `:intent/id` and shows a pending
   indicator on the affected row keyed by that intent id.
2. On `*-completed` with matching `intent-id`, clear the pending indicator. The follow-up
   `:supervisory/pack-manifest-upserted` updates the row's `installed?` / version / trust.
3. On `*-failed` with matching `intent-id`, clear the pending indicator and surface a banner
   carrying the outcome's `:reason` string. The row's prior state is preserved.
4. If neither outcome arrives within an implementation-defined timeout (SHOULD be ≥ 30 s for
   install/compile, ≥ 5 s for uninstall), the renderer SHOULD surface a "still waiting…"
   indicator rather than clearing — the operator can navigate away; the next state refresh
   reconciles.

**Confirmation prompts:**

- Uninstall MUST require confirmation (prevents accidental removal of an in-use pack).
- Trust promotion to `:trusted` MUST require the operator to type or paste the
  `:pack-trust/justification` string before the intent is published.
- Install of a pack whose `:pack/capabilities-requested` is non-empty MUST render the requested
  capabilities and require explicit consent before the intent is published. Per §3.6, the
  installer component still rechecks authorization; the UI confirmation is an ergonomic layer,
  not the security boundary.

**Compile-from-doc UX:** source path input, output pack id + version fields, target-type
multi-select. On success, the resulting `PackManifest` entry appears in the browser (typically
`installed? = false` unless `install?` was checked in the intent).

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

For each entity independently (§8.1), and for pack management as a whole (§8.2), the delta is
satisfied when the boxes below are checked.

### 8.1 Per entity

- [ ] The Clojure schema exists in `components/supervisory-state/schema.clj` matching §2
- [ ] The `supervisory-state` accumulator handles the relevant fine-grained events and the
      corresponding `:supervisory/*-upserted` event is emitted
- [ ] Invariant 6 holds: no other component emits the snapshot events defined in §4.1–§4.5
- [ ] The Rust `supervisory-entities` crate gains the mirror struct with serde round-trip
      tests (absent-field and full-field cases)
- [ ] The `SupervisoryState` gains its new collection + apply helper, and `StateManager`
      dispatches the snapshot event
- [ ] The Rust TUI renders the mapped N5 §3.2.x view per §5 for that entity
- [ ] Empty-state rendering works for mixed fleets where the producer has not yet emitted
      a snapshot

The per-entity block is fully satisfied when all five entities meet the checklist.

### 8.2 Pack management

- [ ] `components/pack-ops` exists and handles the five intent types from §2.6
- [ ] Intent → outcome round-trip works for install, update, uninstall, trust-promote,
      compile-from-doc — each correlating via `:intent/id`
- [ ] Authorization rules in §3.6 reject unauthorized promotions and capability-exceeding
      installs with `:pack/*-failed` + `:reason-code :unauthorized`
- [ ] Doc compilation is deterministic: same inputs produce the same pack hash
- [ ] Doc compilation writes N6 evidence linking the compiled pack back to its source docs
- [ ] Pack Browser UX enforces the confirmation requirements from §5.6 (uninstall prompt,
      trust-promote justification, capability grant consent)
- [ ] Pending-intent UI reconciles on `*-completed` / `*-failed` / timeout per §5.6 state
      machine

The delta is fully satisfied when §8.1 and §8.2 are both met.
