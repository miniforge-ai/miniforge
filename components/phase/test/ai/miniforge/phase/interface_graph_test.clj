;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.phase.interface-graph-test
  "Integration tests for graph-related interface wiring and load-time fail-fast validation.

   Three test groups:

   INTERFACE WIRING — every graph function exposed through phase/interface is callable
   and returns the documented shape:
     build-transition-graph, validate-graph, graph-valid?, validate-pipeline-graph

   LOAD-TIME FAIL-FAST — build-pipeline validates the graph at construction time:
     valid pipeline     → interceptor vector (no anomaly)
     :on-fail dangling  → anomaly with :errors vector at construction

   BACKWARDS COMPATIBILITY — validate-pipeline delegates to validate-pipeline-graph;
   result shape is stable for existing consumers."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Fixture wiring

(def ^:private phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (binding [loader/phase-loader-config-resource phase-test-config-resource]
      (f))
    (phase/reset-phase-loader!)))

;------------------------------------------------------------------------------ Layer 0
;; Named constants

(def ^:private test-phase-budget
  "Minimal resource budget for graph-test fixture phases — small enough that
   tests never actually exhaust it; large enough that budget-checking code
   does not fire spuriously."
  {:tokens 10 :iterations 1 :time-seconds 1})

;------------------------------------------------------------------------------ Layer 0
;; Test phase registrations (unique keywords avoid defmethod collision with interface_test.clj)

(defmethod registry/get-phase-interceptor :graph-test-a
  [config]
  {:name   ::graph-test-a
   :config (registry/merge-with-defaults config)
   :enter  identity
   :leave  identity
   :error  (fn [ctx _ex] ctx)})

(registry/register-phase-defaults!
 :graph-test-a
 {:phase  :graph-test-a
  :agent  :tester
  :gates  []
  :budget test-phase-budget})

(defmethod registry/get-phase-interceptor :graph-test-b
  [config]
  {:name   ::graph-test-b
   :config (registry/merge-with-defaults config)
   :enter  identity
   :leave  identity
   :error  (fn [ctx _ex] ctx)})

(registry/register-phase-defaults!
 :graph-test-b
 {:phase  :graph-test-b
  :agent  :tester
  :gates  []
  :budget test-phase-budget})

;------------------------------------------------------------------------------ Layer 0
;; Pipeline factories

(defn- two-phase-pipeline
  "Minimal structurally-valid pipeline for interface wiring tests."
  []
  [{:phase :graph-test-a}
   {:phase :graph-test-b}])

(defn- pipeline-with-dangling-on-fail
  "Pipeline where :on-fail names a phase absent from the pipeline.
   Drives the load-time fail-fast path in build-pipeline."
  []
  [{:phase :graph-test-a :on-fail :nonexistent-repair-phase}
   {:phase :graph-test-b}])

(defn- workflow-from
  "Wrap a raw pipeline vector in a minimal workflow map."
  [pipeline]
  {:workflow/id       :graph-test-workflow
   :workflow/pipeline pipeline})

;------------------------------------------------------------------------------ Layer 0
;; Named helpers — config-value functions extracted per Rule 002

(defn- phase-registered?
  "True when phase-key has a registered interceptor in the phase registry."
  [phase-key]
  (contains? (phase/list-phases) phase-key))

;------------------------------------------------------------------------------ Layer 0
;; Hand-crafted graphs for direct validate-graph / graph-valid? tests

(def ^:private minimal-valid-graph
  "A TransitionGraph with one phase node and two terminal paths.
   Used to test validate-graph and graph-valid? without build-transition-graph."
  {:nodes          #{:a :failed :completed}
   :edges          [{:from :a :to :failed    :label :on-failure   :meta {}}
                    {:from :a :to :completed :label :already-done :meta {}}]
   :terminal-nodes #{:failed :completed}
   :phase-nodes    #{:a}})

(def ^:private dangling-edge-graph
  "A TransitionGraph with a :to target absent from :nodes.
   Property 1 (check-existence) should flag this."
  {:nodes          #{:a :failed}
   :edges          [{:from :a :to :missing-node :label :next       :meta {}}
                    {:from :a :to :failed       :label :on-failure :meta {}}]
   :terminal-nodes #{:failed}
   :phase-nodes    #{:a}})

;------------------------------------------------------------------------------ Layer 1
;; INTERFACE WIRING — build-transition-graph

(deftest build-transition-graph-accessible-test
  (testing "build-transition-graph is accessible via phase/interface"
    (is (fn? phase/build-transition-graph)))

  (testing "1-arity form returns a graph map for a valid pipeline"
    (let [graph (phase/build-transition-graph (two-phase-pipeline))]
      (is (not (anomaly/anomaly? graph))
          "Valid pipeline must not produce an anomaly from build-transition-graph")
      (is (map? graph))
      (is (contains? graph :nodes))
      (is (contains? graph :edges))
      (is (contains? graph :terminal-nodes))
      (is (contains? graph :phase-nodes))))

  (testing "2-arity form (with opts) is accessible"
    (let [graph (phase/build-transition-graph (two-phase-pipeline) {})]
      (is (not (anomaly/anomaly? graph)))
      (is (map? graph))))

  (testing "phase-nodes contains the pipeline phases"
    (let [graph (phase/build-transition-graph (two-phase-pipeline))]
      (is (contains? (:phase-nodes graph) :graph-test-a))
      (is (contains? (:phase-nodes graph) :graph-test-b))))

  (testing "terminal-nodes is non-empty for a valid pipeline"
    (let [graph (phase/build-transition-graph (two-phase-pipeline))]
      (is (seq (:terminal-nodes graph))))))

;------------------------------------------------------------------------------ Layer 1
;; INTERFACE WIRING — validate-graph

(deftest validate-graph-accessible-test
  (testing "validate-graph is accessible via phase/interface"
    (is (fn? phase/validate-graph)))

  (testing "1-arity form returns map with :valid?, :errors, :warnings"
    (let [result (phase/validate-graph minimal-valid-graph)]
      (is (map? result))
      (is (contains? result :valid?))
      (is (contains? result :errors))
      (is (contains? result :warnings))
      (is (vector? (:errors result)))
      (is (vector? (:warnings result)))))

  (testing "2-arity form (with opts) is accessible and returns same shape"
    (let [result (phase/validate-graph minimal-valid-graph
                                       {:check-interceptors?       true
                                        :interceptor-registered-fn (constantly true)})]
      (is (contains? result :valid?))
      (is (contains? result :errors))
      (is (contains? result :warnings))))

  (testing "returns :valid? true for a structurally sound graph"
    (let [result (phase/validate-graph minimal-valid-graph)]
      (is (true?  (:valid? result)))
      (is (empty? (:errors result)))))

  (testing "returns :valid? false with :errors for a dangling-edge graph"
    (let [result (phase/validate-graph dangling-edge-graph)]
      (is (false? (:valid? result)))
      (is (seq    (:errors result))))))

;------------------------------------------------------------------------------ Layer 1
;; INTERFACE WIRING — graph-valid? (renamed from graph-validator/valid?)

(deftest graph-valid?-accessible-test
  (testing "graph-valid? is accessible via phase/interface"
    (is (fn? phase/graph-valid?)))

  (testing "returns a boolean"
    (is (boolean? (phase/graph-valid? minimal-valid-graph))))

  (testing "returns true for a structurally sound graph"
    (is (true?  (phase/graph-valid? minimal-valid-graph))))

  (testing "returns false for a graph with a dangling edge"
    (is (false? (phase/graph-valid? dangling-edge-graph))))

  (testing "is consistent with validate-graph :valid? field"
    (is (= (phase/graph-valid? minimal-valid-graph)
           (true? (:valid? (phase/validate-graph minimal-valid-graph))))
        "graph-valid? must agree with validate-graph :valid? on valid input")
    (is (= (phase/graph-valid? dangling-edge-graph)
           (true? (:valid? (phase/validate-graph dangling-edge-graph))))
        "graph-valid? must agree with validate-graph :valid? on invalid input")))

;------------------------------------------------------------------------------ Layer 1
;; INTERFACE WIRING — validate-pipeline-graph result shape

(deftest validate-pipeline-graph-result-shape-test
  (testing "always returns map with :valid?, :errors, :warnings keys"
    (let [valid-result   (phase/validate-pipeline-graph (two-phase-pipeline))
          invalid-result (phase/validate-pipeline-graph [])]
      (doseq [result [valid-result invalid-result]]
        (is (contains? result :valid?))
        (is (contains? result :errors))
        (is (contains? result :warnings)))))

  (testing ":errors and :warnings are always vectors"
    (let [valid-result   (phase/validate-pipeline-graph (two-phase-pipeline))
          invalid-result (phase/validate-pipeline-graph [])]
      (doseq [result [valid-result invalid-result]]
        (is (vector? (:errors result)))
        (is (vector? (:warnings result))))))

  (testing ":valid? is true for a structurally sound two-phase pipeline"
    (is (true?  (:valid? (phase/validate-pipeline-graph (two-phase-pipeline)))))
    (is (empty? (:errors (phase/validate-pipeline-graph (two-phase-pipeline))))))

  (testing ":valid? is false for an empty pipeline"
    (is (false? (:valid? (phase/validate-pipeline-graph [])))))

  (testing "pipeline-validation-valid? predicate agrees with :valid? field"
    (is (true?  (phase/pipeline-validation-valid? (phase/validate-pipeline-graph (two-phase-pipeline)))))
    (is (false? (phase/pipeline-validation-valid? (phase/validate-pipeline-graph []))))))

;------------------------------------------------------------------------------ Layer 1
;; LOAD-TIME FAIL-FAST — valid pipeline → no anomaly at build-pipeline

(deftest build-pipeline-valid-pipeline-no-anomaly-test
  (let [result (phase/build-pipeline (workflow-from (two-phase-pipeline)))]
    (testing "returns interceptor vector (no anomaly) for a valid pipeline"
      (is (not (anomaly/anomaly? result))
          "Valid pipeline must not trigger fail-fast at construction")
      (is (vector? result))
      (is (= 2 (count result))))

    (testing "each interceptor has :name, :enter, :leave, :error"
      (doseq [interceptor result]
        (is (map? interceptor))
        (is (keyword?   (:name interceptor)))
        (is (fn? (:enter interceptor)))
        (is (fn? (:leave interceptor)))
        (is (fn? (:error interceptor)))))))

;------------------------------------------------------------------------------ Layer 1
;; LOAD-TIME FAIL-FAST — :on-fail dangling → anomaly at build-pipeline

(deftest build-pipeline-dangling-on-fail-returns-anomaly-test
  (let [result     (phase/build-pipeline (workflow-from (pipeline-with-dangling-on-fail)))
        validation (get-in result [:anomaly/data :validation])
        errors     (:errors validation)]
    (testing "returns anomaly when :on-fail names a nonexistent phase"
      (is (anomaly/anomaly? result)
          "Pipeline with :on-fail → nonexistent must produce anomaly at construction"))

    (testing "anomaly type is :invalid-input"
      (is (= :invalid-input (:anomaly/type result))))

    (testing "anomaly :data carries :pipeline and :validation entries"
      (is (some? (get-in result [:anomaly/data :pipeline])))
      (is (some? validation)))

    (testing "validation has :valid? false and non-empty :errors vector"
      (is (false?  (:valid? validation)))
      (is (vector? errors))
      (is (seq     errors)
          ":errors must contain at least one property-specific diagnostic"))

    (testing "every entry in :errors satisfies anomaly/anomaly?"
      (is (seq errors) "errors must be non-empty before checking individual shapes")
      (is (every? anomaly/anomaly? errors)))))

;------------------------------------------------------------------------------ Layer 1
;; LOAD-TIME FAIL-FAST — empty pipeline → anomaly at build-pipeline

(deftest build-pipeline-empty-pipeline-returns-anomaly-test
  (let [result     (phase/build-pipeline (workflow-from []))
        validation (get-in result [:anomaly/data :validation])]
    (testing "returns anomaly for empty :workflow/pipeline"
      (is (anomaly/anomaly? result)))

    (testing "anomaly :data contains :validation with :errors"
      (is (some? validation))
      (is (seq (:errors validation))))))

;------------------------------------------------------------------------------ Layer 1
;; BACKWARDS COMPATIBILITY — validate-pipeline shape matches validate-pipeline-graph

(deftest validate-pipeline-compat-shape-test
  (let [workflow        (workflow-from (two-phase-pipeline))
        pipeline-result (phase/validate-pipeline workflow)
        graph-result    (phase/validate-pipeline-graph
                         (two-phase-pipeline)
                         {:check-interceptors?       true
                          :interceptor-registered-fn phase-registered?})]
    (testing "returns same key set as validate-pipeline-graph"
      (is (= (set (keys pipeline-result))
             (set (keys graph-result)))
          "validate-pipeline must expose the same key set as validate-pipeline-graph"))

    (testing ":valid? matches validate-pipeline-graph :valid? for same pipeline"
      (is (= (:valid? pipeline-result) (:valid? graph-result))))))

(deftest validate-pipeline-result-contract-test
  (testing "valid pipeline → :valid? true with empty :errors"
    (let [result (phase/validate-pipeline (workflow-from (two-phase-pipeline)))]
      (is (true?  (:valid? result)))
      (is (empty? (:errors result)))))

  (testing "empty pipeline → :valid? false with :errors"
    (let [result (phase/validate-pipeline (workflow-from []))]
      (is (false? (:valid? result)))
      (is (seq    (:errors result)))))

  (testing ":errors and :warnings are always vectors"
    (doseq [workflow [(workflow-from (two-phase-pipeline))
                      (workflow-from [])]]
      (let [result (phase/validate-pipeline workflow)]
        (is (vector? (:errors result)))
        (is (vector? (:warnings result)))))))

(comment
  ;; Quick REPL smoke-tests

  ;; Interface wiring
  (phase/build-transition-graph [{:phase :graph-test-a}
                                  {:phase :graph-test-b}])

  (let [g (phase/build-transition-graph [{:phase :graph-test-a}
                                          {:phase :graph-test-b}])]
    (phase/validate-graph g))

  ;; Fail-fast — dangling :on-fail
  (phase/build-pipeline {:workflow/pipeline
                          [{:phase :graph-test-a :on-fail :nonexistent-repair-phase}
                           {:phase :graph-test-b}]})
  ;; => anomaly map with :anomaly/data :validation :errors

  ;; Backwards compat
  (phase/validate-pipeline {:workflow/pipeline [{:phase :graph-test-a}
                                                {:phase :graph-test-b}]})
  ;; => {:valid? true :errors [] :warnings []}

  :leave-this-here)
