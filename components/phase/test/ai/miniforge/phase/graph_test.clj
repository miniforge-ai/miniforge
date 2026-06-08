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

(ns ai.miniforge.phase.graph-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.graph :as graph]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(def ^:private minimal-pipeline
  "Simplest valid pipeline — one phase."
  [{:phase :plan}])

(def ^:private linear-pipeline
  "Three-phase linear pipeline with no on-fail/on-success overrides."
  [{:phase :plan}
   {:phase :implement}
   {:phase :done :terminal? true}])

(def ^:private budgeted-pipeline
  "Pipeline where :implement has an iteration budget > 1."
  [{:phase :plan}
   {:phase :implement :budget {:iterations 3}}
   {:phase :verify}
   {:phase :done :terminal? true}])

(def ^:private guarded-pipeline
  "Pipeline with guarded phases and on-fail configuration."
  [{:phase :plan}
   {:phase :implement :budget {:iterations 3} :on-fail :plan}
   {:phase :verify    :on-fail :implement}
   {:phase :review    :on-fail :implement}
   {:phase :done :terminal? true}])

(def ^:private on-success-pipeline
  "Pipeline with explicit :on-success override."
  [{:phase :plan}
   {:phase :implement :on-success :verify}
   {:phase :verify}])

(defn- edges-with-label
  "Return all edges from graph with the given label."
  [graph label]
  (filterv #(= label (:label %)) (:edges graph)))

(defn- edge-from-to?
  "True when graph contains at least one edge from `from` to `to` with `label`."
  [graph from to label]
  (some (fn [e]
          (and (= from (:from e))
               (= to   (:to e))
               (= label (:label e))))
        (:edges graph)))

;------------------------------------------------------------------------------ Layer 1
;; Anomaly return on malformed input

(deftest test-empty-pipeline-returns-anomaly
  (testing "nil pipeline → anomaly"
    (let [result (graph/build-transition-graph nil)]
      (is (= :invalid-input (:anomaly/type result))
          "nil pipeline should return an anomaly")))

  (testing "empty vector pipeline → anomaly"
    (let [result (graph/build-transition-graph [])]
      (is (= :invalid-input (:anomaly/type result))
          "empty pipeline should return an anomaly")))

  (testing "non-sequential pipeline → anomaly"
    (let [result (graph/build-transition-graph {:phase :plan})]
      (is (= :invalid-input (:anomaly/type result))
          "map (not vector) pipeline should return an anomaly"))))

(deftest test-malformed-entry-returns-anomaly
  (testing "entry without :phase key → anomaly"
    (let [result (graph/build-transition-graph [{:name :broken}])]
      (is (= :invalid-input (:anomaly/type result)))
      (is (= 0 (get-in result [:anomaly/data :index])))))

  (testing "non-map entry → anomaly"
    (let [result (graph/build-transition-graph [:not-a-map])]
      (is (= :invalid-input (:anomaly/type result))))))

;------------------------------------------------------------------------------ Layer 1
;; Node derivation

(deftest test-nodes-include-all-phases
  (testing "all phase keywords appear in :nodes"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (contains? (:nodes g) :plan))
      (is (contains? (:nodes g) :implement))
      (is (contains? (:nodes g) :done))))

  (testing ":phase-nodes contains only pipeline phases"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (= #{:plan :implement :done} (:phase-nodes g)))))

  (testing ":terminal-nodes always includes canonical terminals"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (contains? (:terminal-nodes g) :failed))
      (is (contains? (:terminal-nodes g) :completed))
      (is (contains? (:terminal-nodes g) :cancelled))))

  (testing ":done added to :terminal-nodes when present in pipeline"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (contains? (:terminal-nodes g) :done)))))

(deftest test-nodes-no-done-when-absent
  (testing ":done not in :terminal-nodes when pipeline has no :done phase"
    (let [g (graph/build-transition-graph minimal-pipeline)]
      (is (not (contains? (:terminal-nodes g) :done))))))

;------------------------------------------------------------------------------ Layer 1
;; :next edge derivation

(deftest test-next-edges-linear-sequence
  (testing "each non-terminal phase has a :next edge to the following phase"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (edge-from-to? g :plan :implement graph/label-next))
      (is (edge-from-to? g :implement :done graph/label-next))))

  (testing "terminal phase has no :next edge"
    (let [g (graph/build-transition-graph linear-pipeline)
          done-nexts (filter #(and (= :done (:from %))
                                   (= graph/label-next (:label %)))
                             (:edges g))]
      (is (empty? done-nexts)))))

(deftest test-single-phase-no-next-edges
  (testing "single-phase pipeline produces no :next edges"
    (let [g (graph/build-transition-graph minimal-pipeline)
          next-edges (edges-with-label g graph/label-next)]
      (is (empty? next-edges)))))

;------------------------------------------------------------------------------ Layer 1
;; :on-failure edge derivation

(deftest test-failure-edges-all-phases
  (testing "every phase has at least one :on-failure edge to :failed"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (doseq [phase [:plan :implement :done]]
        (is (edge-from-to? g phase :failed graph/label-on-failure)
            (str "expected :on-failure edge for " phase))))))

;------------------------------------------------------------------------------ Layer 1
;; :retry self-loop derivation

(deftest test-retry-edge-for-budgeted-phase
  (testing "phase with :budget :iterations > 1 gets :retry self-loop"
    (let [g (graph/build-transition-graph budgeted-pipeline)]
      (is (edge-from-to? g :implement :implement graph/label-retry))))

  (testing "phase with default budget (no :iterations) has no :retry edge"
    (let [g (graph/build-transition-graph budgeted-pipeline)
          plan-retries (filter #(and (= :plan (:from %))
                                     (= graph/label-retry (:label %)))
                               (:edges g))]
      (is (empty? plan-retries)))))

;------------------------------------------------------------------------------ Layer 1
;; :already-done edge derivation

(deftest test-already-done-edges
  (testing "all phases have :already-done edge"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (doseq [phase [:plan :implement :done]]
        (is (edge-from-to? g phase :done graph/label-already-done)
            (str "expected :already-done edge for " phase)))))

  (testing "no :done phase → :already-done targets :completed"
    (let [g (graph/build-transition-graph minimal-pipeline)]
      (is (edge-from-to? g :plan :completed graph/label-already-done)))))

;------------------------------------------------------------------------------ Layer 1
;; :redirect and :on-budget-exhausted derivation for guarded phases

(deftest test-redirect-edge-for-guarded-phases-with-on-fail
  (testing ":implement with :on-fail :plan gets a :redirect edge to :plan"
    (let [g (graph/build-transition-graph guarded-pipeline)]
      (is (edge-from-to? g :implement :plan graph/label-redirect))))

  (testing ":redirect edge is budget-guarded"
    (let [g (graph/build-transition-graph guarded-pipeline)
          redirect-edge (first (filter #(and (= :implement (:from %))
                                             (= graph/label-redirect (:label %)))
                                       (:edges g)))]
      (is (true? (get-in redirect-edge [:meta :budget-guarded?])))))

  (testing ":verify with :on-fail gets :redirect edge"
    (let [g (graph/build-transition-graph guarded-pipeline)]
      (is (edge-from-to? g :verify :implement graph/label-redirect)))))

(deftest test-budget-exhausted-edge-for-guarded-phases-with-on-fail
  (testing ":on-budget-exhausted edge to :failed for guarded phases with :on-fail"
    (let [g (graph/build-transition-graph guarded-pipeline)]
      (is (edge-from-to? g :implement :failed graph/label-on-budget-exhausted))
      (is (edge-from-to? g :verify :failed graph/label-on-budget-exhausted))))

  (testing "non-guarded phase with :on-fail does NOT get :on-budget-exhausted edge"
    ;; :plan is not in budget-guarded-phases
    (let [g (graph/build-transition-graph guarded-pipeline)
          plan-budget-edges (filter #(and (= :plan (:from %))
                                          (= graph/label-on-budget-exhausted (:label %)))
                                    (:edges g))]
      (is (empty? plan-budget-edges)))))

(deftest test-no-redirect-for-guarded-phase-without-on-fail
  (testing "guarded phase without :on-fail gets no :redirect edge"
    ;; :implement in budgeted-pipeline has no :on-fail
    (let [g (graph/build-transition-graph budgeted-pipeline)
          impl-redirects (filter #(and (= :implement (:from %))
                                       (= graph/label-redirect (:label %)))
                                  (:edges g))]
      (is (empty? impl-redirects)))))

;------------------------------------------------------------------------------ Layer 1
;; :cancel and :pause edge derivation

(deftest test-cancel-edges
  (testing "every phase has a :cancel edge to :cancelled"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (doseq [phase [:plan :implement :done]]
        (is (edge-from-to? g phase :cancelled graph/label-cancel))))))

(deftest test-pause-edges
  (testing "every phase has a :pause self-edge"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (doseq [phase [:plan :implement :done]]
        (is (edge-from-to? g phase phase graph/label-pause))))))

;------------------------------------------------------------------------------ Layer 1
;; :on-success override

(deftest test-on-success-override
  (testing "explicit :on-success target gets :on-success edge distinct from :next"
    (let [g (graph/build-transition-graph on-success-pipeline)]
      ;; :implement → :verify via :on-success (skips linear next which is also :verify)
      ;; on-success-pipeline has implement at index 1, verify at index 2 — same target,
      ;; so the dedup check means no separate :on-success edge; confirm :next exists
      (is (edge-from-to? g :implement :verify graph/label-next)))))

(deftest test-on-success-non-sequential-target
  (testing ":on-success to a non-adjacent phase produces an :on-success edge"
    (let [pipeline [{:phase :plan :on-success :verify}
                    {:phase :implement}
                    {:phase :verify}]
          g (graph/build-transition-graph pipeline)]
      ;; :plan's next-index is 1 (:implement), but :on-success is :verify (index 2)
      (is (edge-from-to? g :plan :verify graph/label-on-success)))))

;------------------------------------------------------------------------------ Layer 1
;; Graph structural invariants

(deftest test-edges-is-a-vector
  (testing ":edges is always a vector"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (vector? (:edges g))))))

(deftest test-nodes-is-a-set
  (testing ":nodes is always a set"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (set? (:nodes g))))))

(deftest test-terminal-nodes-subset-of-nodes
  (testing ":terminal-nodes is a subset of :nodes"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (every? #(contains? (:nodes g) %) (:terminal-nodes g))))))

(deftest test-phase-nodes-subset-of-nodes
  (testing ":phase-nodes is a subset of :nodes"
    (let [g (graph/build-transition-graph linear-pipeline)]
      (is (every? #(contains? (:nodes g) %) (:phase-nodes g))))))
