# miniforge.ai — Change Train Specification

**Version:** 0.1.0
**Status:** Draft
**Date:** 2026-01-22

---

## 1. Overview

### 1.1 Purpose

Change Train is miniforge's high-value wedge product: **multi-repo change trains with policy-as-code gates and semantic intent enforcement for infrastructure/platform work**.

This spec defines two new components:
- **repo-dag**: Multi-repo dependency graph with topological ordering
- **pr-train**: Linked PR choreography with coordinated state management

### 1.2 Design Principles

1. **Explicit dependencies**: Repo relationships are modeled as a DAG, not implicit
2. **Topological correctness**: PRs merge in dependency order, never out of sequence
3. **Train-as-unit**: A set of related PRs is managed as a single coherent change
4. **Fail-safe rollback**: Partial merges can be reverted cleanly
5. **Evidence-driven**: Every train produces an auditable evidence bundle

### 1.3 Key Differentiators

| Capability | Claude Code | Fleet Mode MVP | Change Train |
|------------|-------------|----------------|--------------|
| Watch PRs across repos | No | Yes | Yes |
| Dependency ordering | No | No | Yes |
| Linked PR sets | No | No | Yes |
| Semantic intent validation | No | No | Yes |
| Evidence bundles | No | Partial | Yes |

---

## 2. Repo DAG Component

### 2.1 Responsibility

Model explicit dependencies between repositories for topologically-sorted merge ordering.

### 2.2 Schema

```clojure
;; Repository node in the DAG
(def RepoNode
  [:map
   [:repo/url string?]
   [:repo/name string?]
   [:repo/org {:optional true} string?]
   [:repo/type [:enum :terraform-module :terraform-live :kubernetes
                :argocd :application :library :documentation]]
   [:repo/layer [:enum :foundations :infrastructure :platform :application :adapters]]
   [:repo/default-branch {:default "main"} string?]
   [:repo/watch-config {:optional true}
    [:map
     [:labels-include {:optional true} [:vector string?]]
     [:labels-exclude {:optional true} [:vector string?]]
     [:paths-include {:optional true} [:vector string?]]
     [:paths-exclude {:optional true} [:vector string?]]]]])

;; Edge representing a dependency relationship
(def RepoEdge
  [:map
   [:edge/from string?]                    ; repo name
   [:edge/to string?]                      ; repo name
   [:edge/constraint
    [:enum :module-before-live             ; TF modules before live infra
           :infra-before-k8s               ; Infrastructure before K8s manifests
           :k8s-before-argocd              ; Manifests before ArgoCD apps
           :library-before-consumer        ; Libraries before consumers
           :schema-before-impl]]           ; Schema changes before implementations
   [:edge/merge-ordering
    [:enum :sequential                     ; Must merge in order
           :parallel-ok                    ; Can merge in parallel if both ready
           :same-pr-train]]                ; Must be in same PR train
   [:edge/validation {:optional true}
    [:map
     [:require-ci-pass? {:default true} boolean?]
     [:require-plan-clean? {:default false} boolean?]
     [:custom-gate {:optional true} keyword?]]]])

;; The complete DAG
(def RepoDag
  [:map
   [:dag/id uuid?]
   [:dag/name string?]
   [:dag/description {:optional true} string?]
   [:dag/repos [:vector RepoNode]]
   [:dag/edges [:vector RepoEdge]]
   ;; Computed at runtime
   [:dag/topo-order {:optional true} [:vector string?]]
   [:dag/layers {:optional true} [:map-of keyword? [:vector string?]]]])
```

### 2.3 Protocol

```clojure
(defprotocol RepoDagManager
  ;; CRUD
  (create-dag [this name description]
    "Create a new empty DAG")

  (add-repo [this dag-id repo-config]
    "Add a repository node to the DAG")

  (remove-repo [this dag-id repo-name]
    "Remove a repository from the DAG (and its edges)")

  (add-edge [this dag-id from-repo to-repo constraint merge-ordering]
    "Add a dependency edge between repos")

  (remove-edge [this dag-id from-repo to-repo]
    "Remove a dependency edge")

  ;; Queries
  (get-dag [this dag-id]
    "Retrieve a DAG by ID")

  (compute-topo-order [this dag-id]
    "Compute topological sort of repos. Returns ordered vector or error if cyclic.")

  (affected-repos [this dag-id changed-repo]
    "Given a changed repo, return all downstream repos that may be affected")

  (upstream-repos [this dag-id repo-name]
    "Return all repos that this repo depends on")

  (merge-order [this dag-id pr-set]
    "Given a set of PRs across repos, return valid merge order")

  ;; Validation
  (validate-dag [this dag-id]
    "Check for cycles, orphans, invalid references. Returns {:valid? bool :errors [...]}"))
```

### 2.4 Implementation Notes

#### Topological Sort Algorithm

Use Kahn's algorithm for deterministic ordering:

```clojure
(defn topo-sort [dag]
  (let [nodes (set (map :repo/name (:dag/repos dag)))
        edges (:dag/edges dag)
        in-degree (reduce (fn [acc {:edge/to to}]
                           (update acc to (fnil inc 0)))
                         (zipmap nodes (repeat 0))
                         edges)
        adj (reduce (fn [acc {:edge/from from :edge/to to}]
                     (update acc from (fnil conj []) to))
                   {}
                   edges)]
    ;; Kahn's algorithm
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                       (filter #(zero? (in-degree %)) nodes))
           in-deg in-degree
           result []]
      (if (empty? queue)
        (if (= (count result) (count nodes))
          {:success true :order result}
          {:success false :error :cycle-detected})
        (let [node (peek queue)
              neighbors (get adj node [])]
          (recur (into (pop queue)
                       (filter #(zero? (dec (in-deg %)))
                               neighbors))
                 (reduce #(update %1 %2 dec) in-deg neighbors)
                 (conj result node)))))))
```

#### Cycle Detection

Cycles MUST be rejected. When detected:
1. Return specific nodes involved in cycle
2. Suggest which edge to remove
3. Never allow DAG creation with cycles

#### Layer Inference

If repo layer not specified, infer from type:
- `:terraform-module` → `:foundations`
- `:terraform-live` → `:infrastructure`
- `:kubernetes` → `:platform`
- `:argocd` → `:platform`
- `:application` → `:application`
- `:library` → `:foundations`

---

## 3. PR Train Component

### 3.1 Responsibility

Manage a set of related PRs as a single unit with coordinated state and merge ordering.

### 3.2 Schema

```clojure
;; Individual PR in a train
(def TrainPR
  [:map
   [:pr/repo string?]
   [:pr/number pos-int?]
   [:pr/url string?]
   [:pr/branch string?]
   [:pr/title string?]
   [:pr/status [:enum :draft :open :reviewing :changes-requested
                :approved :merging :merged :closed :failed]]
   [:pr/merge-order pos-int?]             ; Position in train (1-indexed)
   [:pr/depends-on [:vector pos-int?]]    ; PR numbers that must merge first
   [:pr/blocks [:vector pos-int?]]        ; PR numbers waiting on this
   [:pr/ci-status [:enum :pending :running :passed :failed :skipped]]
   [:pr/gate-results {:optional true} [:vector GateResult]]
   [:pr/intent {:optional true} TaskIntent]])

;; The complete PR train
(def PRTrain
  [:map
   [:train/id uuid?]
   [:train/name string?]
   [:train/description {:optional true} string?]
   [:train/dag-id uuid?]                  ; Reference to repo DAG
   [:train/status [:enum :drafting :open :reviewing :merging
                   :merged :failed :rolled-back :abandoned]]
   [:train/prs [:vector TrainPR]]

   ;; Aggregate state (computed)
   [:train/blocking-prs [:vector pos-int?]]
   [:train/ready-to-merge [:vector pos-int?]]
   [:train/progress {:optional true}
    [:map
     [:total pos-int?]
     [:merged nat-int?]
     [:approved nat-int?]
     [:pending nat-int?]
     [:failed nat-int?]]]

   ;; Rollback configuration
   [:train/rollback-plan {:optional true}
    [:map
     [:trigger [:enum :ci-failure :gate-failure :manual :timeout]]
     [:action [:enum :revert-all :revert-to-checkpoint :pause]]
     [:checkpoint {:optional true} pos-int?]  ; Last known good PR number
     [:prs-to-revert [:vector pos-int?]]]]

   ;; Evidence
   [:train/evidence-bundle-id {:optional true} uuid?]

   ;; Timing
   [:train/created-at inst?]
   [:train/updated-at inst?]
   [:train/merged-at {:optional true} inst?]])

;; Evidence bundle for audit trail
(def EvidenceBundle
  [:map
   [:evidence/id uuid?]
   [:evidence/train-id uuid?]
   [:evidence/created-at inst?]
   [:evidence/prs
    [:vector
     [:map
      [:pr/repo string?]
      [:pr/number pos-int?]
      [:evidence/artifacts
       [:vector
        [:map
         [:type [:enum :terraform-plan :atlantis-log :ci-log
                 :gate-results :intent-validation :approval-record]]
         [:content string?]
         [:hash string?]
         [:timestamp inst?]]]]]]]
   [:evidence/summary
    [:map
     [:total-prs pos-int?]
     [:gates-passed nat-int?]
     [:gates-failed nat-int?]
     [:human-approvals nat-int?]
     [:semantic-violations nat-int?]]]
   [:evidence/miniforge-version string?]])
```

### 3.3 Protocol

```clojure
(defprotocol PRTrainManager
  ;; Lifecycle
  (create-train [this name dag-id description]
    "Create a new PR train linked to a repo DAG")

  (add-pr [this train-id repo pr-number]
    "Add a PR to the train")

  (remove-pr [this train-id pr-number]
    "Remove a PR from the train")

  (link-prs [this train-id]
    "Auto-compute depends-on/blocks from DAG topology")

  ;; State management
  (sync-pr-status [this train-id]
    "Fetch latest status for all PRs from GitHub")

  (update-pr-status [this train-id pr-number new-status]
    "Update status for a single PR")

  ;; Queries
  (get-train [this train-id]
    "Retrieve a train by ID")

  (get-blocking [this train-id]
    "Return PRs that are blocking train progress")

  (get-ready-to-merge [this train-id]
    "Return PRs that can merge now (all deps merged, approved, CI passed)")

  (get-progress [this train-id]
    "Return progress summary: total, merged, approved, pending, failed")

  ;; Actions
  (merge-next [this train-id]
    "Merge the next ready PR in topological order")

  (merge-all-ready [this train-id]
    "Merge all currently ready PRs (respecting order)")

  (pause-train [this train-id reason]
    "Pause all train activity")

  (resume-train [this train-id]
    "Resume paused train")

  (rollback [this train-id reason]
    "Execute rollback plan: revert merged PRs")

  (abandon-train [this train-id reason]
    "Mark train as abandoned, optionally close PRs")

  ;; Evidence
  (generate-evidence-bundle [this train-id]
    "Collect and bundle all evidence artifacts")

  (get-evidence-bundle [this bundle-id]
    "Retrieve evidence bundle"))
```

### 3.4 State Machine

```
                                    ┌─────────────┐
                          ┌─────────│  DRAFTING   │─────────┐
                          │         └──────┬──────┘         │
                          │                │ open           │ abandon
                          │                ▼                │
                          │         ┌─────────────┐         │
                          │    ┌────│    OPEN     │────┐    │
                          │    │    └──────┬──────┘    │    │
                          │    │           │ review    │    │
                          │    │           ▼           │    │
                          │    │    ┌─────────────┐    │    │
                          │    │    │  REVIEWING  │────┼────┤
                          │    │    └──────┬──────┘    │    │
                          │    │           │ all       │    │
                          │    │           │ approved  │    │
                          │    │           ▼           │    │
                          │    │    ┌─────────────┐    │    │
                          │    │    │   MERGING   │────┼────┤
                          │    │    └──────┬──────┘    │    │
                          │    │           │           │    │
                    ┌─────┴────┴───────────┼───────────┴────┴─────┐
                    │                      │                       │
                    ▼                      ▼                       ▼
             ┌─────────────┐        ┌─────────────┐         ┌─────────────┐
             │   FAILED    │        │   MERGED    │         │  ABANDONED  │
             └──────┬──────┘        └─────────────┘         └─────────────┘
                    │
                    │ rollback
                    ▼
             ┌─────────────┐
             │ ROLLED-BACK │
             └─────────────┘
```

### 3.5 Merge Ordering Rules

1. **Topological constraint**: A PR can only merge after all its `depends-on` PRs have merged
2. **Status constraint**: PR must be in `approved` status with `ci-status: :passed`
3. **Gate constraint**: All gates must pass (configurable per-DAG)
4. **Intent constraint**: Semantic intent validation must pass (if configured)

Ready-to-merge calculation:

```clojure
(defn ready-to-merge? [train pr]
  (let [deps-merged? (every? #(= :merged (:pr/status (get-pr train %)))
                             (:pr/depends-on pr))
        approved? (= :approved (:pr/status pr))
        ci-passed? (= :passed (:pr/ci-status pr))
        gates-passed? (every? :gate/passed? (:pr/gate-results pr))]
    (and deps-merged? approved? ci-passed? gates-passed?)))
```

### 3.6 Rollback Strategy

When a train fails mid-merge:

1. **Identify checkpoint**: Last successfully merged PR
2. **Collect revert targets**: All PRs merged after checkpoint
3. **Create revert PRs**: In reverse topological order
4. **Execute reverts**: Merge revert PRs (also in order)
5. **Update evidence**: Record rollback in evidence bundle

```clojure
(defn execute-rollback [train reason]
  (let [merged-prs (->> (:train/prs train)
                        (filter #(= :merged (:pr/status %)))
                        (sort-by :pr/merge-order >))  ; Reverse order
        revert-prs (map create-revert-pr merged-prs)]
    ;; Merge reverts in reverse order
    (doseq [revert revert-prs]
      (merge-pr revert))
    {:status :rolled-back
     :reverted-prs (map :pr/number merged-prs)
     :reason reason}))
```

---

## 4. Integration Points

### 4.1 With Existing Components

| Component | Integration |
|-----------|-------------|
| `workflow` | New workflow type `:change-train` that uses PR train |
| `loop/gates` | Gates run per-PR in train |
| `policy` | Policy packs apply to train as a whole |
| `task` | Tasks can reference semantic intent for validation |
| `reporting` | Dashboard views for train status |
| `orchestrator` | Coordinates multi-repo work across train |

### 4.2 With Fleet Mode

PR Train extends Fleet Mode:
- Fleet watches repos and PRs independently
- PR Train groups related PRs into coherent changes
- Dashboard shows both individual PRs and trains

```
┌─────────────────────────────────────────────────────────────────────────┐
│ MINIFORGE FLEET - 4 repos │ 23 PRs │ 2 trains │ 3 active               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ ▶ ACTIVE TRAINS (2)                                                     │
│   ├─ [TRAIN] Add User Auth (#123 → #124 → #125)                        │
│   │   └─ Status: REVIEWING │ 1/3 merged │ Blocked: #124 needs review   │
│   └─ [TRAIN] Migrate DB Schema (#200 → #201)                           │
│       └─ Status: MERGING │ 1/2 merged │ Ready: #201                    │
│                                                                         │
│ ○ STANDALONE PRs (18)                                                   │
│   ├─ acme/frontend#456 [APPROVED] "Fix nav bug"                        │
│   └─ ... 17 more                                                        │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ [T] New Train  [M] Merge Ready  [V] View Train  [↑↓] Navigate  [Q] Quit │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.3 CLI Commands

```bash
# DAG management
miniforge dag create "kiddom-infra" --description "Kiddom infrastructure repos"
miniforge dag add-repo kiddom-infra github.com/kiddom/terraform-modules --type terraform-module
miniforge dag add-repo kiddom-infra github.com/kiddom/terraform --type terraform-live
miniforge dag add-edge kiddom-infra terraform-modules terraform --constraint module-before-live
miniforge dag show kiddom-infra
miniforge dag validate kiddom-infra

# Train management
miniforge train create "Add user auth" --dag kiddom-infra
miniforge train add-pr user-auth terraform-modules#123
miniforge train add-pr user-auth terraform#124
miniforge train add-pr user-auth k8s-manifests#125
miniforge train link user-auth  # Auto-compute dependencies
miniforge train status user-auth
miniforge train merge-next user-auth
miniforge train merge-all user-auth
miniforge train rollback user-auth --reason "CI failure in production"

# Evidence
miniforge train evidence user-auth --output evidence-bundle.edn
```

---

## 5. Semantic Intent Integration

### 5.1 Task Intent Schema

```clojure
(def TaskIntent
  [:map
   [:intent/type [:enum :create :import :modify :delete :migrate]]

   ;; Invariants that MUST hold
   [:intent/invariants
    [:vector
     [:map
      [:invariant/id keyword?]
      [:invariant/description string?]
      [:invariant/check
       [:map
        [:type [:enum :plan-output :diff-analysis :state-comparison]]
        [:condition any?]]]]]]

   ;; Actions forbidden for this intent type
   [:intent/forbidden-actions [:vector keyword?]]

   ;; Required evidence artifacts
   [:intent/required-evidence [:vector keyword?]]])

;; Example: IMPORT intent
{:intent/type :import
 :intent/invariants
 [{:invariant/id :no-creates
   :invariant/description "Import must not create new resources"
   :invariant/check {:type :plan-output
                     :condition {:creates 0}}}
  {:invariant/id :no-destroys
   :invariant/description "Import must not destroy resources"
   :invariant/check {:type :plan-output
                     :condition {:destroys 0}}}
  {:invariant/id :preserve-import-blocks
   :invariant/description "Import blocks must not be removed"
   :invariant/check {:type :diff-analysis
                     :condition {:removed-patterns [#"import\s*\{"]
                                 :count 0}}}]
 :intent/forbidden-actions
 [:remove-import-block :create-new-resource :destroy-existing-resource]
 :intent/required-evidence
 [:terraform-plan-output :atlantis-apply-log :state-list-before-after]}
```

### 5.2 Intent Validation Gate

```clojure
(defrecord SemanticIntentGate [id config]
  Gate
  (check [_this artifact context]
    (let [task (:task context)
          intent (:task/intent task)]
      (if-not intent
        (pass-result id :semantic-intent)  ; No intent = pass
        (let [violations (validate-invariants artifact intent)]
          (if (empty? violations)
            (pass-result id :semantic-intent)
            (fail-result id :semantic-intent
                         (map invariant->error violations)))))))
  (gate-id [_this] id)
  (gate-type [_this] :semantic-intent))
```

---

## 6. Deliverables

### Phase 1: Core Components

- [ ] `components/repo-dag/` - Schema, protocol, in-memory implementation
- [ ] `components/pr-train/` - Schema, protocol, state machine
- [ ] Tests for both components
- [ ] Integration with existing `schema` component

### Phase 2: GitHub Integration

- [ ] GitHub API adapter for PR status sync
- [ ] Merge operation implementation
- [ ] Revert PR creation
- [ ] Webhook handlers for PR events

### Phase 3: CLI & Dashboard

- [ ] CLI commands for dag and train management
- [ ] Fleet Mode dashboard extension
- [ ] Train visualization (dependency graph)

### Phase 4: Intent & Evidence

- [ ] Semantic intent schema in `task` component
- [ ] `SemanticIntentGate` in `loop/gates`
- [ ] Evidence bundle generation
- [ ] Evidence serialization (EDN + optional JSON)

---

## 7. Open Questions

1. **Partial train progress**: Can a train be "done" if some PRs are abandoned?
2. **Cross-org trains**: Should trains span GitHub organizations?
3. **Concurrent trains**: Can PRs be in multiple trains simultaneously?
4. **Auto-train detection**: Should we auto-detect related PRs and suggest trains?
5. **Train templates**: Pre-defined train structures for common change types?

---

## 8. References

- [operational-modes.spec](./operational-modes.spec) — Fleet Mode foundation
- [miniforge.spec](./miniforge.spec) — Core product spec
- [architecture.spec](./architecture.spec) — Component architecture
