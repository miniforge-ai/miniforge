# Workflow Interceptor Architecture — Specification

**Version:** 0.1.0
**Status:** Draft
**Author:** Christopher Lester
**Date:** 2026-01-22

---

## 1. Problem Statement

### Current Complexity

The miniforge workflow configuration suffers from:

1. **Per-phase verbosity** — Each phase requires ~20 lines with deeply nested maps
2. **Dual architecture** — Hard-coded phase executors (core.clj) vs flexible EDN configs (configurable.clj)
3. **Switch statement factories** — 70+ lines of `case` statements for agent/gate creation
4. **Unused fields** — `:phase/actions`, `:phase/metrics`, redundant `:phase/validation-steps`
5. **Magic keywords** — `:repair-strategy :fix-errors` has no implementation mapping

### Ixi Patterns to Adopt

From the Ixi codebase:

1. **Multimethod registry** — Interceptors registered via `defmethod`, not case switches
2. **Composition via vectors** — Routes compose interceptors as `[auth validate handler]`
3. **Integrant/EDN config** — Configuration declares what, code provides how
4. **Separation of bricks** — `interceptors-*` component separate from `routes-*` component
5. **Layered structure** — Strata 0 (primitives) → Strata 1 (interceptor defs)

---

## 2. Core Principles

### 2.1 Workflows Are Interceptor Chains

A workflow is a vector of interceptors. Each phase is an interceptor with `:enter` and `:leave` functions.

```clojure
;; Pedestal-style interceptor
{:name ::implement
 :enter (fn [ctx] (run-implementation ctx))
 :leave (fn [ctx] (collect-metrics ctx))
 :error (fn [ctx ex] (handle-repair ctx ex))}
```

### 2.2 Registry Over Switch

Register phase handlers via multimethod, not case statements:

```clojure
;; Define once in each domain module
(defmethod get-phase-interceptor :plan [opts]
  (create-interceptor {:agent :planner ...}))

(defmethod get-phase-interceptor :implement [opts]
  (create-interceptor {:agent :implementer ...}))
```

### 2.3 Configuration Is Data, Behavior Is Code

Configuration says **what** runs. Code in registries defines **how**.

```clojure
;; BAD: Config defines behavior
{:phase/repair-strategy :fix-errors}  ; Magic keyword

;; GOOD: Config declares intent, registry provides behavior
{:phase :implement :gates [:syntax :lint]}
;; Registry knows: :syntax → syntax-gate implementation
```

### 2.4 Composition Over Configuration

Instead of configuring every detail, compose from defaults:

```clojure
;; BAD: 20 lines per phase
{:phase/id :implement
 :phase/name "Implementation"
 :phase/agent :implementer
 :phase/inner-loop {:max-iterations 3 ...}
 :phase/gates [...]
 :phase/budget {...}
 ...}

;; GOOD: Compose from registry defaults
{:phase :implement}  ; Registry provides defaults
;; Or override specific things:
{:phase :implement :budget {:tokens 50000}}
```

---

## 3. Architecture

### 3.1 Component Structure (Polylith)

```
components/
├── workflow/           ; Orchestration
│   ├── interface.clj   ; Public API
│   ├── runner.clj      ; Pipeline executor
│   └── state.clj       ; Execution state
│
├── phase/              ; Phase definitions (NEW)
│   ├── interface.clj   ; Registry multimethod
│   ├── plan.clj        ; :plan phase
│   ├── implement.clj   ; :implement phase
│   ├── verify.clj      ; :verify phase
│   ├── review.clj      ; :review phase
│   └── release.clj     ; :release phase
│
├── gate/               ; Gate definitions (NEW)
│   ├── interface.clj   ; Registry multimethod
│   ├── syntax.clj      ; :syntax gate
│   ├── lint.clj        ; :lint gate
│   ├── test.clj        ; :tests gate
│   └── policy.clj      ; Policy gates
│
└── loop/               ; Inner loop (existing)
    ├── interface.clj
    └── inner.clj
```

### 3.2 Registry Pattern

**Phase Registry** (`phase/interface.clj`):

```clojure
(ns ai.miniforge.phase.interface)

;; Multimethod dispatches on :phase keyword
(defmulti get-phase-interceptor
  "Get interceptor for a phase configuration."
  :phase)

;; Default implementation throws
(defmethod get-phase-interceptor :default [{:keys [phase]}]
  (throw (ex-info (str "Unknown phase: " phase) {:phase phase})))
```

**Phase Implementation** (`phase/implement.clj`):

```clojure
(ns ai.miniforge.phase.implement
  (:require [ai.miniforge.phase.interface :refer [get-phase-interceptor]]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.loop.interface :as loop]))

;; Layer 0: Pure defaults
(def default-config
  {:agent :implementer
   :gates [:syntax :lint]
   :budget {:tokens 30000 :iterations 5}})

;; Layer 1: Interceptor factory
(defmethod get-phase-interceptor :implement
  [config]
  (let [merged (merge default-config config)]
    {:name ::implement
     :enter (fn [ctx] (run-phase ctx merged))
     :leave (fn [ctx] (record-metrics ctx))
     :error (fn [ctx ex] (attempt-repair ctx ex merged))}))
```

**Gate Registry** (`gate/interface.clj`):

```clojure
(ns ai.miniforge.gate.interface)

(defmulti get-gate
  "Get gate implementation for keyword."
  identity)

(defmethod get-gate :default [k]
  ;; Unknown gates pass through (warn in dev)
  {:name k
   :check (constantly true)
   :repair nil})
```

**Gate Implementation** (`gate/syntax.clj`):

```clojure
(ns ai.miniforge.gate.syntax
  (:require [ai.miniforge.gate.interface :refer [get-gate]]))

(defmethod get-gate :syntax [_]
  {:name :syntax
   :check (fn [artifact ctx]
            (syntax/valid? (:content artifact)))
   :repair (fn [artifact errors ctx]
             (syntax/fix (:content artifact) errors))})
```

### 3.3 Workflow Configuration (Simplified EDN)

**Before** (80 lines for simple workflow):

```clojure
{:workflow/id :simple-test-v1
 :workflow/version "1.0.0"
 :workflow/phases
 [{:phase/id :plan
   :phase/name "Planning"
   :phase/agent :planner
   :phase/actions [:create-plan]
   :phase/inner-loop
   {:max-iterations 2
    :validation-steps [:plan-complete]
    :repair-strategy :adjust-plan}
   :phase/gates [:plan-complete]
   :phase/budget {:tokens 3000 :time-seconds 60 :iterations 2}
   :phase/next [{:target :implement}]
   :phase/metrics [:planning-time]}
  ;; ... 40 more lines
  ]}
```

**After** (15 lines):

```clojure
{:workflow/id :simple-test
 :workflow/version "1.0.0"

 ;; Pipeline: vector of phase configs
 :workflow/pipeline
 [{:phase :plan}                           ; Uses registry defaults
  {:phase :implement :budget {:tokens 50000}}  ; Override budget
  {:phase :verify :on-fail :implement}     ; Explicit retry transition
  {:phase :done}]}
```

### 3.4 Pipeline Runner

```clojure
(ns ai.miniforge.workflow.runner
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.gate.interface :as gate]))

;; Layer 0: Build interceptor chain from config
(defn build-pipeline [workflow-config]
  (->> (:workflow/pipeline workflow-config)
       (mapv phase/get-phase-interceptor)))

;; Layer 1: Execute pipeline (Pedestal-style)
(defn execute [pipeline initial-ctx]
  (reduce
    (fn [ctx interceptor]
      (try
        ((:enter interceptor) ctx)
        (catch Exception ex
          (if-let [error-fn (:error interceptor)]
            (error-fn ctx ex)
            (throw ex)))))
    initial-ctx
    pipeline))
```

---

## 4. Configuration Schema

### 4.1 Workflow Schema (Minimal)

```clojure
(def Workflow
  [:map
   [:workflow/id keyword?]
   [:workflow/version string?]
   [:workflow/pipeline [:vector PhaseConfig]]])

(def PhaseConfig
  [:map
   [:phase keyword?]                        ; Required: phase type
   [:budget {:optional true} Budget]        ; Optional override
   [:gates {:optional true} [:vector keyword?]]  ; Optional override
   [:on-fail {:optional true} keyword?]     ; Transition on failure
   [:on-success {:optional true} keyword?]]) ; Transition on success (default: next)

(def Budget
  [:map
   [:tokens {:optional true} pos-int?]
   [:iterations {:optional true} pos-int?]
   [:time-seconds {:optional true} pos-int?]])
```

### 4.2 Interceptor Schema

```clojure
(def Interceptor
  [:map
   [:name keyword?]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

(def Gate
  [:map
   [:name keyword?]
   [:check fn?]                ; (fn [artifact ctx] -> bool)
   [:repair {:optional true} fn?]])  ; (fn [artifact errors ctx] -> artifact)
```

---

## 5. Execution Flow

### 5.1 Phase Execution

```
┌─────────────────────────────────────────────────────────────────┐
│                     PHASE INTERCEPTOR                           │
├─────────────────────────────────────────────────────────────────┤
│  :enter                                                         │
│  ├── 1. Resolve agent from registry                            │
│  ├── 2. Resolve gates from registry                            │
│  ├── 3. Run inner loop (generate → validate → repair)          │
│  └── 4. Return ctx with :artifact                              │
│                                                                 │
│  :leave                                                         │
│  └── Record metrics, update execution state                    │
│                                                                 │
│  :error                                                         │
│  ├── If retryable: attempt repair via inner loop               │
│  ├── If :on-fail specified: transition to that phase           │
│  └── Else: propagate error                                      │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Inner Loop (Unchanged)

The existing inner loop remains: `generate → validate → repair`

But gates are now resolved from registry, not created via switch statement.

---

## 6. Migration Path

### Phase 1: Create Registries

1. Create `phase/` component with multimethod registry
2. Create `gate/` component with multimethod registry
3. Move existing agent/gate creation to registry methods

### Phase 2: Simplify Configuration

1. Define minimal workflow schema
2. Add defaults to phase registry entries
3. Update loader to handle simplified config

### Phase 3: Remove Legacy

1. Remove `agent_factory.clj` switch statements
2. Remove unused fields from schema
3. Update existing workflow EDN files

---

## 7. Benefits

| Aspect | Before | After |
|--------|--------|-------|
| Config lines per phase | ~20 | ~1-3 |
| Adding new phase type | Modify agent_factory.clj | Add new file, register method |
| Adding new gate type | Modify agent_factory.clj | Add new file, register method |
| Understanding phase behavior | Read factory + agent + config | Read single phase module |
| Defaults | Scattered/duplicated | Centralized in registry |
| Validation | Optional, incomplete | Schema enforced |

---

## 8. Example: Full Workflow

```clojure
;; resources/workflows/standard-sdlc.edn
{:workflow/id :standard-sdlc
 :workflow/version "2.0.0"

 :workflow/pipeline
 [{:phase :spec}
  {:phase :plan}
  {:phase :implement
   :budget {:tokens 50000}
   :gates [:syntax :lint :no-secrets]}
  {:phase :verify
   :on-fail :implement}
  {:phase :review
   :on-fail :implement}
  {:phase :release}
  {:phase :done}]

 ;; Optional: global config
 :workflow/config
 {:max-tokens 100000
  :max-iterations 50}}
```

Compare to current 145-line canonical-sdlc-v1.edn — this is **~20 lines** with equivalent power.

---

## 9. Open Questions

1. **Integrant for lifecycle?** — Ixi uses Integrant for DI. Worth adopting for miniforge?
2. **Parallel phases?** — Current config supports `:parallel-phases`. Keep or simplify?
3. **Metrics collection** — Currently per-phase. Move to interceptor `:leave`?
4. **Response chain pattern** — Ixi tracks operation history. Useful for debugging?

---

## 10. References

- [Pedestal Interceptors](http://pedestal.io/reference/interceptors)
- [Ixi interceptors-spicybrain](../../../ixi/components/interceptors-spicybrain/)
- [Current workflow config](../../../components/workflow/resources/workflows/)
- [miniforge.spec](./miniforge.spec) — Main product specification
