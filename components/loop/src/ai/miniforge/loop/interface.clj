(ns ai.miniforge.loop.interface
  "Public API for the loop component.
   Provides inner loop (generate -> validate -> repair) and outer loop (SDLC phases)
   state machines for autonomous artifact production."
  (:require
   [ai.miniforge.loop.inner :as inner]
   [ai.miniforge.loop.outer :as outer]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.repair :as repair]
   [ai.miniforge.loop.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def InnerLoopState schema/InnerLoopState)
(def InnerLoopResult schema/InnerLoopResult)
(def GateResult schema/GateResult)
(def GateConfig schema/GateConfig)
(def RepairAttempt schema/RepairAttempt)
(def LoopMetrics schema/LoopMetrics)
(def LoopBudget schema/LoopBudget)
(def OuterLoopState schema/OuterLoopState)

;; Enum values
(def inner-loop-states schema/inner-loop-states)
(def outer-loop-phases schema/outer-loop-phases)
(def gate-types schema/gate-types)
(def repair-strategies schema/repair-strategies)
(def termination-reasons schema/termination-reasons)

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Gate gates/Gate)
(def RepairStrategy repair/RepairStrategy)

;------------------------------------------------------------------------------ Layer 1
;; Inner Loop API

(defn create-inner-loop
  "Create a new inner loop state for a task.

   Arguments:
   - task - Map with :task/id and :task/type
   - context - Optional context map

   Options (via context):
   - :max-iterations - Maximum generate/repair cycles (default 5)
   - :budget - Budget constraints map with :max-tokens, :max-cost-usd, :max-duration-ms

   Example:
     (create-inner-loop {:task/id (random-uuid) :task/type :implement}
                        {:max-iterations 3
                         :budget {:max-tokens 50000}})"
  ([task] (inner/create-inner-loop task {}))
  ([task context] (inner/create-inner-loop task context)))

(defn run-inner-loop
  "Run the inner loop to completion.

   Arguments:
   - loop-state - Loop state created by create-inner-loop
   - generate-fn - Function (fn [task context] -> {:artifact map :tokens int})
   - gates - Sequence of Gate implementations
   - strategies - Sequence of RepairStrategy implementations
   - context - Context map with :logger, :repair-fn, etc.

   Returns:
   {:success boolean
    :artifact artifact-map (if success)
    :iterations int
    :metrics {:tokens int :cost-usd float :duration-ms int}
    :termination {:reason keyword :message string}}

   Example:
     (run-inner-loop loop-state
                     my-generate-fn
                     (default-gates)
                     (default-strategies)
                     {:logger logger :repair-fn my-repair-fn})"
  [loop-state generate-fn gates strategies context]
  (inner/run-inner-loop loop-state generate-fn gates strategies context))

(defn run-simple
  "Simplified inner loop runner with default gates and strategies.

   Arguments:
   - task - Map with :task/id and :task/type
   - generate-fn - Function (fn [task context] -> {:artifact map :tokens int})
   - context - Context map

   Options (via context):
   - :max-iterations - Max iterations (default 5)
   - :logger - Logger instance
   - :repair-fn - Repair function for LLM strategy

   Example:
     (run-simple {:task/id (random-uuid) :task/type :implement}
                 my-generate-fn
                 {:logger logger})"
  [task generate-fn context]
  (inner/run-simple task generate-fn context))

;; Step-by-step control

(defn generate-step
  "Execute the generate step.
   Used for fine-grained control of the loop.

   Arguments:
   - loop-state - Current loop state
   - generate-fn - Generation function
   - context - Context map

   Returns updated loop state."
  [loop-state generate-fn context]
  (inner/generate-step loop-state generate-fn context))

(defn validate-step
  "Execute the validate step.
   Used for fine-grained control of the loop.

   Arguments:
   - loop-state - Current loop state
   - gates - Sequence of Gate implementations
   - context - Context map

   Returns updated loop state."
  [loop-state gates context]
  (inner/validate-step loop-state gates context))

(defn repair-step
  "Execute the repair step.
   Used for fine-grained control of the loop.

   Arguments:
   - loop-state - Current loop state
   - strategies - Sequence of RepairStrategy implementations
   - context - Context map

   Returns updated loop state."
  [loop-state strategies context]
  (inner/repair-step loop-state strategies context))

;; Termination checks

(defn should-terminate?
  "Check if the loop should terminate.
   Returns {:terminate? bool :reason keyword} or nil."
  [loop-state]
  (inner/should-terminate? loop-state))

(defn terminal-state?
  "Check if a state is terminal (complete, failed, or escalated)."
  [state]
  (inner/terminal-state? state))

;------------------------------------------------------------------------------ Layer 1
;; Gates API

(defn syntax-gate
  "Create a syntax validation gate.
   Checks that code artifacts can be parsed without errors.

   Example:
     (syntax-gate)
     (syntax-gate :my-syntax-gate)"
  ([] (gates/syntax-gate))
  ([id] (gates/syntax-gate id))
  ([id config] (gates/syntax-gate id config)))

(defn lint-gate
  "Create a lint validation gate.
   Options:
   - :fail-on-warning? - If true, warnings cause failure (default false)

   Example:
     (lint-gate)
     (lint-gate :strict-lint {:fail-on-warning? true})"
  ([] (gates/lint-gate))
  ([id] (gates/lint-gate id))
  ([id config] (gates/lint-gate id config)))

(defn test-gate
  "Create a test validation gate.
   Options:
   - :test-fn - Function (fn [artifact context] -> {:passed? bool :errors [...]})"
  ([] (gates/test-gate))
  ([id] (gates/test-gate id))
  ([id config] (gates/test-gate id config)))

(defn policy-gate
  "Create a policy validation gate.
   Options:
   - :policies - Vector of policy keywords to check
                 Supported: :no-secrets, :no-todos, :require-docstrings

   Example:
     (policy-gate :security {:policies [:no-secrets :no-todos]})"
  ([] (gates/policy-gate))
  ([id] (gates/policy-gate id))
  ([id config] (gates/policy-gate id config)))

(defn custom-gate
  "Create a custom validation gate.
   Arguments:
   - id - Unique gate identifier
   - check-fn - Function (fn [artifact context] -> gate-result-map)"
  ([id check-fn] (gates/custom-gate id check-fn))
  ([id type-kw check-fn] (gates/custom-gate id type-kw check-fn)))

(defn default-gates
  "Create a default set of gates for code artifacts.
   Options:
   - :lint-fail-on-warning? - Lint gate fails on warnings (default false)
   - :policies - Vector of policy keywords (default [:no-secrets])"
  [& opts]
  (apply gates/default-gates opts))

(defn minimal-gates
  "Create a minimal set of gates (syntax only)."
  []
  (gates/minimal-gates))

(defn strict-gates
  "Create a strict set of gates for production code.
   Includes all policies and fails on warnings."
  []
  (gates/strict-gates))

(defn check-gate
  "Run a single gate check on an artifact.
   Returns gate result map with :gate/id, :gate/type, :gate/passed?, etc."
  [gate artifact context]
  (gates/check gate artifact context))

(defn run-gates
  "Run multiple gates against an artifact.
   Options:
   - :fail-fast? - Stop on first failure (default false)

   Returns:
   {:passed? bool
    :results [gate-result...]
    :failed-gates [gate-id...]
    :errors [error...]}"
  [gates artifact context & opts]
  (apply gates/run-gates gates artifact context opts))

;; Gate result helpers

(defn pass-result
  "Create a passing gate result."
  [gate-id gate-type & opts]
  (apply gates/pass-result gate-id gate-type opts))

(defn fail-result
  "Create a failing gate result."
  [gate-id gate-type errors & opts]
  (apply gates/fail-result gate-id gate-type errors opts))

(defn make-error
  "Create a gate error map."
  [code message & opts]
  (apply gates/make-error code message opts))

;------------------------------------------------------------------------------ Layer 1
;; Repair API

(defn llm-fix-strategy
  "Create an LLM-based repair strategy.
   Options:
   - :max-tokens - Maximum tokens for repair attempt (default 4000)"
  ([] (repair/llm-fix-strategy))
  ([config] (repair/llm-fix-strategy config)))

(defn retry-strategy
  "Create a simple retry strategy.
   Options:
   - :delay-ms - Delay before retry in milliseconds (default 1000)"
  ([] (repair/retry-strategy))
  ([config] (repair/retry-strategy config)))

(defn escalate-strategy
  "Create an escalation strategy.
   This strategy always signals escalation to the outer loop."
  ([] (repair/escalate-strategy))
  ([config] (repair/escalate-strategy config)))

(defn default-strategies
  "Create a default ordered list of repair strategies.
   Order: LLM fix -> Retry -> Escalate"
  []
  (repair/default-strategies))

(defn attempt-repair
  "Attempt to repair an artifact using available strategies.
   Options:
   - :max-attempts - Maximum repair attempts (default 3)

   Returns:
   {:success? bool
    :artifact artifact (if success)
    :errors [...] (if failure)
    :attempts int}"
  [strategies artifact errors context & opts]
  (apply repair/attempt-repair strategies artifact errors context opts))

;; Repair result helpers

(defn repair-success
  "Create a successful repair result."
  [strategy artifact & opts]
  (apply repair/repair-success strategy artifact opts))

(defn repair-failure
  "Create a failed repair result."
  [strategy errors & opts]
  (apply repair/repair-failure strategy errors opts))

;------------------------------------------------------------------------------ Layer 1
;; Outer Loop API (P1 - stubs)

(defn create-outer-loop
  "Create a new outer loop state.
   NOTE: Outer loop is a P1 stub implementation.

   Arguments:
   - spec - Map with :spec/id and spec content
   - context - Optional context map"
  ([spec] (outer/create-outer-loop spec {}))
  ([spec context] (outer/create-outer-loop spec context)))

(defn advance-phase
  "Advance to the next phase.
   NOTE: Stub implementation."
  [loop-state context]
  (outer/advance-phase loop-state context))

(defn rollback-phase
  "Rollback to a previous phase.
   NOTE: Stub implementation."
  [loop-state target-phase context]
  (outer/rollback-phase loop-state target-phase context))

(defn get-current-phase
  "Get the current phase of the outer loop."
  [loop-state]
  (outer/get-current-phase loop-state))

(defn run-outer-loop
  "Run the outer loop through all phases.
   NOTE: Stub implementation."
  [loop-state context]
  (outer/run-outer-loop loop-state context))

;; Phase definitions

(def phases outer/phases)
(def phase-definitions outer/phase-definitions)

(defn get-phase-definition
  "Get the definition for a phase."
  [phase]
  (outer/get-phase-definition phase))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; === Inner Loop Usage ===

  ;; Simple usage with defaults
  (def task {:task/id (random-uuid) :task/type :implement})

  (defn my-generate [_task _ctx]
    {:artifact {:artifact/id (random-uuid)
                :artifact/type :code
                :artifact/content "(defn hello [] \"world\")"}
     :tokens 100})

  (run-simple task my-generate {:max-iterations 3})
  ;; => {:success true, :artifact {...}, :iterations 1, ...}

  ;; Fine-grained control
  (def loop-state (create-inner-loop task {:max-iterations 5}))

  (-> loop-state
      (generate-step my-generate {})
      (validate-step (default-gates) {})
      :loop/state)
  ;; => :complete

  ;; === Gates Usage ===

  (def gates [(syntax-gate)
              (lint-gate :my-lint {:fail-on-warning? true})
              (policy-gate :security {:policies [:no-secrets]})])

  (def artifact {:artifact/id (random-uuid)
                 :artifact/type :code
                 :artifact/content "(defn hello [] \"world\")"})

  (run-gates gates artifact {})
  ;; => {:passed? true, :results [...], :failed-gates [], :errors []}

  ;; Custom gate
  (def max-length-gate
    (custom-gate :max-length
                 (fn [artifact _ctx]
                   (if (> (count (:artifact/content artifact "")) 1000)
                     (fail-result :max-length :custom
                                  [(make-error :too-long "Content too long")])
                     (pass-result :max-length :custom)))))

  ;; === Repair Usage ===

  (def strategies (default-strategies))

  (attempt-repair strategies artifact
                  [{:code :syntax-error :message "Bad syntax"}]
                  {:repair-fn (fn [a _e _c]
                                {:success? true
                                 :artifact (assoc a :artifact/content "(fixed)")
                                 :tokens-used 50})})

  ;; === Outer Loop Usage (stubs) ===

  (def spec {:spec/id (random-uuid)
             :description "Implement auth feature"})

  (def outer (create-outer-loop spec {}))

  (get-current-phase outer)
  ;; => :spec

  (def advanced (advance-phase outer {}))
  (get-current-phase advanced)
  ;; => :plan

  :leave-this-here)
