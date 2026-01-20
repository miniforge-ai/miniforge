(ns ai.miniforge.loop.schema
  "Malli schemas for loop state, gates, and repair strategies.
   Layer 0: Base types and registries
   Layer 1: Composite schemas (InnerLoopState, GateResult, RepairResult)"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Base types and enums

(def inner-loop-states
  "Possible states for the inner loop state machine."
  [:pending :generating :validating :repairing :complete :failed :escalated])

(def outer-loop-phases
  "Phases in the outer loop SDLC cycle."
  [:spec :plan :design :implement :verify :review :release :observe])

(def gate-types
  "Types of validation gates."
  [:syntax :lint :test :policy :custom])

(def repair-strategies
  "Available repair strategies."
  [:llm-fix :retry :escalate])

(def termination-reasons
  "Reasons for loop termination."
  [:gates-passed :max-iterations :budget-exhausted :timeout :unrecoverable-error :manual-stop])

(def registry
  "Malli registry for loop schema types."
  {;; Identifiers
   :id/uuid        uuid?
   :id/string      [:string {:min 1}]

   ;; Inner loop types
   :loop/id        :id/uuid
   :loop/state     (into [:enum] inner-loop-states)
   :loop/type      [:enum :inner :outer]

   ;; Gate types
   :gate/id        keyword?
   :gate/type      (into [:enum] gate-types)
   :gate/passed?   boolean?

   ;; Repair types
   :repair/strategy (into [:enum] repair-strategies)

   ;; Termination
   :termination/reason (into [:enum] termination-reasons)

   ;; Common types
   :common/timestamp inst?
   :common/non-neg-int [:int {:min 0}]
   :common/pos-number [:double {:min 0.0}]})

;------------------------------------------------------------------------------ Layer 1
;; Gate schemas

(def GateError
  "Schema for a single gate error."
  [:map {:registry registry}
   [:code keyword?]
   [:message :id/string]
   [:location {:optional true}
    [:map
     [:file {:optional true} :id/string]
     [:line {:optional true} :common/non-neg-int]
     [:column {:optional true} :common/non-neg-int]]]])

(def GateResult
  "Schema for the result of running a validation gate."
  [:map {:registry registry}
   [:gate/id :gate/id]
   [:gate/type :gate/type]
   [:gate/passed? :gate/passed?]
   [:gate/errors {:optional true} [:vector GateError]]
   [:gate/warnings {:optional true} [:vector GateError]]
   [:gate/duration-ms {:optional true} :common/non-neg-int]])

(def GateConfig
  "Schema for gate configuration."
  [:map {:registry registry}
   [:gate/id :gate/id]
   [:gate/type :gate/type]
   [:gate/enabled? {:optional true :default true} boolean?]
   [:gate/config {:optional true} [:map-of keyword? any?]]
   [:gate/applies-to {:optional true} [:set keyword?]]])

;------------------------------------------------------------------------------ Layer 1
;; Repair schemas

(def RepairAttempt
  "Schema for a single repair attempt."
  [:map {:registry registry}
   [:repair/id :id/uuid]
   [:repair/strategy :repair/strategy]
   [:repair/iteration :common/non-neg-int]
   [:repair/errors [:vector GateError]]
   [:repair/success? boolean?]
   [:repair/duration-ms {:optional true} :common/non-neg-int]
   [:repair/tokens-used {:optional true} :common/non-neg-int]])

;------------------------------------------------------------------------------ Layer 1
;; Loop metrics

(def LoopMetrics
  "Schema for loop execution metrics."
  [:map {:registry registry}
   [:tokens {:optional true} :common/non-neg-int]
   [:cost-usd {:optional true} :common/pos-number]
   [:duration-ms {:optional true} :common/non-neg-int]
   [:generate-calls {:optional true} :common/non-neg-int]
   [:repair-calls {:optional true} :common/non-neg-int]])

(def LoopBudget
  "Schema for loop execution budget constraints."
  [:map {:registry registry}
   [:max-tokens {:optional true} :common/non-neg-int]
   [:max-cost-usd {:optional true} :common/pos-number]
   [:max-duration-ms {:optional true} :common/non-neg-int]
   [:max-iterations {:optional true} :common/non-neg-int]])

;------------------------------------------------------------------------------ Layer 1
;; Inner loop state schema

(def InnerLoopState
  "Schema for the inner loop state machine.
   Tracks the generate -> validate -> repair cycle."
  [:map {:registry registry}
   ;; Required fields
   [:loop/id :loop/id]
   [:loop/type [:= :inner]]
   [:loop/state :loop/state]
   [:loop/iteration :common/non-neg-int]

   ;; Task reference (minimal inline representation)
   [:loop/task
    [:map
     [:task/id :id/uuid]
     [:task/type keyword?]]]

   ;; Artifact (optional until generated)
   [:loop/artifact {:optional true}
    [:map
     [:artifact/id :id/uuid]
     [:artifact/type keyword?]
     [:artifact/content {:optional true} any?]]]

   ;; Configuration
   [:loop/config {:optional true}
    [:map
     [:max-iterations {:optional true} :common/non-neg-int]
     [:budget {:optional true} LoopBudget]]]

   ;; Execution state
   [:loop/gate-results {:optional true} [:vector GateResult]]
   [:loop/repair-history {:optional true} [:vector RepairAttempt]]
   [:loop/errors {:optional true} [:vector GateError]]

   ;; Metrics
   [:loop/metrics {:optional true} LoopMetrics]

   ;; Timestamps
   [:loop/created-at {:optional true} :common/timestamp]
   [:loop/updated-at {:optional true} :common/timestamp]

   ;; Termination
   [:loop/termination {:optional true}
    [:map
     [:reason :termination/reason]
     [:message {:optional true} :id/string]]]])

;------------------------------------------------------------------------------ Layer 1
;; Inner loop result

(def InnerLoopResult
  "Schema for the result of running an inner loop to completion."
  [:map {:registry registry}
   [:success boolean?]
   [:artifact {:optional true}
    [:map
     [:artifact/id :id/uuid]
     [:artifact/type keyword?]
     [:artifact/content {:optional true} any?]]]
   [:iterations :common/non-neg-int]
   [:metrics {:optional true} LoopMetrics]
   [:termination {:optional true}
    [:map
     [:reason :termination/reason]
     [:message {:optional true} :id/string]]]])

;------------------------------------------------------------------------------ Layer 1
;; Outer loop state schema (P1 - stub)

(def OuterLoopPhase
  "Schema for outer loop phase definition."
  [:map {:registry registry}
   [:phase/id keyword?]
   [:phase/agent {:optional true} keyword?]
   [:phase/artifacts {:optional true} [:vector keyword?]]
   [:phase/requires {:optional true} [:vector keyword?]]])

(def OuterLoopState
  "Schema for the outer loop state machine.
   Tracks the SDLC phases: plan -> design -> implement -> verify -> review -> release -> observe"
  [:map {:registry registry}
   [:loop/id :loop/id]
   [:loop/type [:= :outer]]
   [:loop/phase (into [:enum] outer-loop-phases)]
   [:loop/spec {:optional true} [:map [:spec/id :id/uuid]]]
   [:loop/artifacts {:optional true} [:map-of keyword? :id/uuid]]
   [:loop/history {:optional true} [:vector [:map
                                              [:phase keyword?]
                                              [:timestamp :common/timestamp]
                                              [:outcome keyword?]]]]
   [:loop/config {:optional true} [:map-of keyword? any?]]
   [:loop/created-at {:optional true} :common/timestamp]
   [:loop/updated-at {:optional true} :common/timestamp]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate InnerLoopState
  (m/validate InnerLoopState
              {:loop/id (random-uuid)
               :loop/type :inner
               :loop/state :pending
               :loop/iteration 0
               :loop/task {:task/id (random-uuid)
                           :task/type :implement}})
  ;; => true

  ;; Validate GateResult
  (m/validate GateResult
              {:gate/id :lint-clj
               :gate/type :lint
               :gate/passed? false
               :gate/errors [{:code :unused-binding
                              :message "Unused binding: x"
                              :location {:file "core.clj" :line 10}}]})
  ;; => true

  ;; Validate InnerLoopResult
  (m/validate InnerLoopResult
              {:success true
               :artifact {:artifact/id (random-uuid)
                          :artifact/type :code
                          :artifact/content "(defn hello [] \"world\")"}
               :iterations 2
               :metrics {:tokens 1500 :duration-ms 3000}})
  ;; => true

  ;; Explain invalid state
  (m/explain InnerLoopState {:loop/id "not-uuid" :loop/state :invalid})

  :leave-this-here)
