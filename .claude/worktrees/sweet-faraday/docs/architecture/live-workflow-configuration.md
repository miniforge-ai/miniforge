# Live Workflow Configuration Architecture

This document explains how workflow EDN configs become live, executable configuration
that the Meta loop can self-update at runtime.

## Configuration Flow

```text
┌─────────────────────────────────────────────────────────────────────┐
│ WORKFLOW CONFIGURATION LIFECYCLE                                     │
└─────────────────────────────────────────────────────────────────────┘

1. DESIGN TIME (Human/Meta Loop creates configs)
   ↓
   ┌──────────────────────────────────────────────────────────────────┐
   │ EDN Config Files                                                  │
   │ components/workflow/resources/workflows/                          │
   │                                                                   │
   │ - canonical-sdlc-v1.0.0.edn   (baseline)                         │
   │ - lean-sdlc-v1.0.0.edn        (alternative)                      │
   │ - proposed-v1.1.0.edn         (Meta loop generated)              │
   └──────────────────────────────────────────────────────────────────┘
   ↓
2. VALIDATION (Schema + DAG correctness)
   ↓
   ┌──────────────────────────────────────────────────────────────────┐
   │ Malli Schema Validation                                           │
   │ components/workflow/resources/schemas/workflow.edn                │
   │                                                                   │
   │ - Type checking (keyword?, string?, int?)                        │
   │ - Structure validation (required fields present)                 │
   │ - DAG validation (no cycles, reachable nodes)                    │
   └──────────────────────────────────────────────────────────────────┘
   ↓
3. STORAGE (Versioned in Heuristic component)
   ↓
   ┌──────────────────────────────────────────────────────────────────┐
   │ Heuristic Storage                                                 │
   │ ~/.miniforge/heuristics/                                          │
   │                                                                   │
   │ workflow-config/1.0.0.edn                                         │
   │ workflow-config/1.1.0.edn                                         │
   │ workflow-config/_active → "1.0.0"                                 │
   └──────────────────────────────────────────────────────────────────┘
   ↓
4. ACTIVATION (Set active workflow per task type)
   ↓
   ┌──────────────────────────────────────────────────────────────────┐
   │ Active Workflow Mapping                                           │
   │ ~/.miniforge/workflows/active.edn                                 │
   │                                                                   │
   │ {:feature :canonical-sdlc-v1                                      │
   │  :bugfix :lean-sdlc-v1                                            │
   │  :refactor :canonical-sdlc-v1                                     │
   │  :component :canonical-sdlc-v1}                                   │
   └──────────────────────────────────────────────────────────────────┘
   ↓
5. RUNTIME (Load and execute)
   ↓
   ┌──────────────────────────────────────────────────────────────────┐
   │ Workflow Executor                                                 │
   │                                                                   │
   │ (workflow/execute-workflow                                        │
   │   (workflow/get-active-workflow :feature)                         │
   │   user-spec)                                                      │
   └──────────────────────────────────────────────────────────────────┘
```

## Live Configuration Mechanism

### Hot-Reload Support

Workflows can be updated without restarting miniforge:

```clojure
;; Current execution
(let [workflow (load-workflow :canonical-sdlc-v1 "1.0.0")]
  (execute-workflow workflow spec))

;; Meta loop updates config
(save-workflow new-workflow-v1.1.0)
(set-active-workflow :feature :canonical-sdlc-v1 "1.1.0")

;; Next execution (different task)
(let [workflow (load-workflow :canonical-sdlc-v1 "1.1.0")]
  (execute-workflow workflow spec))  ; Uses new config!
```

**Key insight:** Each execution loads the workflow config at start time. Updating the
active pointer affects new executions, not in-flight ones.

### Active Workflow Resolution

```clojure
(defn get-active-workflow [task-type]
  ;; 1. Read active.edn mapping
  (let [active-map (read-active-config)
        workflow-id (get active-map task-type)]

    ;; 2. Load workflow from heuristic store
    (heuristic/get-active-heuristic workflow-id)))
```

## Meta Loop Self-Update

The Meta loop can update workflows at runtime:

```text
┌─────────────────────────────────────────────────────────────────────┐
│ META LOOP WORKFLOW UPDATE CYCLE                                      │
└─────────────────────────────────────────────────────────────────────┘

1. OBSERVE
   Collect metrics from recent executions:
   - canonical-sdlc-v1: 80% success, 45min avg, 50k tokens
   - Review phase: 40% of total time (bottleneck!)

2. ANALYZE
   Identify optimization opportunities:
   - Review max-rounds: 3 → could reduce to 2
   - Hypothesis: Save time without hurting quality

3. PROPOSE
   Generate new workflow config:

   {:workflow/id :canonical-sdlc-v1
    :workflow/version "1.1.0"  ; Incremented
    :workflow/phases
    [{:phase/id :review
      :phase/review-loop
      {:max-rounds 2}}]}  ; Changed from 3

4. VALIDATE
   Check correctness:
   (validate-workflow proposed-config)
   ;; - Schema valid?
   ;; - DAG valid?
   ;; - Budgets reasonable?

5. SAVE
   Version in heuristic store:
   (heuristic/save-heuristic
     :workflow-config
     "1.1.0"
     proposed-config)

6. EXPERIMENT (A/B Test)
   Route 50% of tasks to new version:
   - 10 tasks on v1.0.0
   - 10 tasks on v1.1.0
   - Collect metrics

7. MEASURE
   Compare outcomes:
   - v1.0.0: 80% success, 45min, 50k tokens
   - v1.1.0: 79% success, 35min, 48k tokens
   - Result: Faster with similar quality!

8. DECIDE
   If better, promote:
   (set-active-workflow :feature :canonical-sdlc-v1 "1.1.0")

   Update active.edn:
   {:feature :canonical-sdlc-v1  ; Now uses v1.1.0
    ...}

9. MONITOR
   Track v1.1.0 in production:
   - Continue collecting metrics
   - Ready to rollback if issues
   - Iterate again (v1.2.0?)
```

## Integration with Existing Components

### Policy Component (Phase 1)

Gates are evaluated at phase boundaries:

```clojure
;; From workflow config
{:phase/id :implement-code
 :phase/gates [:syntax-valid :lint-clean :tests-pass]}

;; At runtime
(defn advance-to-next-phase? [phase artifact]
  (let [gates (:phase/gates phase)
        result (policy/evaluate artifact
                               (:phase/id phase)
                               gates)]
    (:passed? result)))
```

**Already built!** Just wire in the phase transitions.

### Heuristic Component (Phase 1)

Workflows are stored as versioned heuristics:

```clojure
;; Save workflow
(heuristic/save-heuristic
  :workflow-config
  "1.0.0"
  {:workflow/id :canonical-sdlc-v1
   :workflow/phases [...]})

;; Retrieve workflow
(heuristic/get-heuristic :workflow-config "1.0.0")

;; Get active version
(heuristic/get-active-heuristic :workflow-config)
```

**Already built!** Workflows are just another heuristic type.

### Loop Component (Future)

Inner loops execute within phases:

```clojure
;; From workflow config
{:phase/id :implement-code
 :phase/inner-loop
 {:max-iterations 15
  :validation-steps [:tests-pass :lint-clean]
  :repair-strategy :fix-code-errors}}

;; At runtime
(defn execute-phase-inner-loop [phase artifact agent]
  (loop-engine/execute
    {:max-iterations (get-in phase [:phase/inner-loop :max-iterations])
     :generate-fn (partial agent/generate agent)
     :validate-fn (partial validate-steps (get-in phase [:phase/inner-loop :validation-steps]))
     :repair-fn (partial repair-artifact agent (get-in phase [:phase/inner-loop :repair-strategy]))
     :artifact artifact}))
```

**To be built in Phase 2.**

## Safety and Rollback

### Version Control

All workflow configs are versioned:

```text
~/.miniforge/heuristics/
  workflow-config/
    1.0.0.edn  (stable baseline)
    1.1.0.edn  (proposed by Meta loop)
    1.2.0.edn  (experimental)
    _active → "1.0.0"
```

### Rollback

If a new workflow causes issues:

```clojure
;; Quick rollback
(set-active-workflow :feature :canonical-sdlc-v1 "1.0.0")

;; Or system-wide
(reset-all-active-workflows "1.0.0")
```

### Audit Log

All workflow changes are logged:

```clojure
{:timestamp #inst "2026-01-21T12:00:00"
 :event :workflow-activated
 :workflow-id :canonical-sdlc-v1
 :version "1.1.0"
 :previous-version "1.0.0"
 :reason "Meta loop: Review bottleneck optimization"
 :approver :meta-loop}
```

## Configuration Precedence

```text
1. Task-specific override (highest priority)
   (execute-workflow explicit-workflow spec)

2. Task type mapping
   (get-active-workflow :bugfix) → :lean-sdlc-v1

3. Default fallback
   (get-active-workflow :unknown) → :canonical-sdlc-v1

4. System default (lowest priority)
   :canonical-sdlc-v1
```

## Performance Considerations

### Caching

Workflow configs are cached in memory:

```clojure
(def workflow-cache
  (atom {}))

(defn load-workflow [workflow-id version]
  (if-let [cached (get @workflow-cache [workflow-id version])]
    cached
    (let [loaded (heuristic/get-heuristic workflow-id version)]
      (swap! workflow-cache assoc [workflow-id version] loaded)
      loaded)))
```

### Cache Invalidation

When Meta loop updates a workflow:

```clojure
(defn set-active-workflow [task-type workflow-id version]
  ;; 1. Update active.edn
  (update-active-config task-type workflow-id)

  ;; 2. Invalidate cache
  (swap! workflow-cache dissoc [workflow-id :active])

  ;; 3. Broadcast to other instances (if distributed)
  (broadcast-config-change workflow-id version))
```

## Example: Gradual Rollout

Meta loop can gradually roll out new workflows:

```clojure
;; Week 1: A/B test (10% traffic)
(set-rollout-percentage :canonical-sdlc-v1.1 0.1)

;; Week 2: Expand (50% traffic)
(set-rollout-percentage :canonical-sdlc-v1.1 0.5)

;; Week 3: Full rollout (100% traffic)
(set-active-workflow :feature :canonical-sdlc-v1 "1.1.0")
```

## Summary

**Live Configuration Enabled:**

- ✅ Workflows load from EDN at runtime
- ✅ Validated before activation
- ✅ Stored as versioned heuristics
- ✅ Hot-reload without restart
- ✅ Meta loop can self-update
- ✅ Gradual rollout supported
- ✅ Rollback capability
- ✅ Audit logging

**Integration:**

- ✅ Policy component (gates) - Phase 1
- ✅ Heuristic component (storage) - Phase 1
- ⏳ Loop component (inner loops) - Phase 2
- ⏳ Meta loop (proposals) - Phase 3

The configuration is **truly live** - the Meta loop can experiment with and deploy new
SDLC workflows without code changes or restarts.
