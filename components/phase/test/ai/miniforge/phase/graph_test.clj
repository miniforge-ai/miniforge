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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph :as graph]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(def ^:private budget-test-iteration-count
  "Iteration count used to exercise the retry-edge derivation path. Must be > 1
   to produce a :retry self-loop; 3 gives one retry cycle beyond the initial
   attempt without oversizing the fixture."
  3)

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
   {:phase :implement :budget {:iterations budget-test-iteration-count}}
   {:phase :verify}
   {:phase :done :terminal? true}])

(def ^:private guarded-pipeline
  "Pipeline with guarded phases and on-fail configuration."
  [{:phase :plan}
   {:phase :implement :budget {:iterations budget-test-iteration-count} :on-fail :plan}
   {:phase :verify    :on-fail :implement}
   {:phase :review    :on-fail :implement}
   {:phase :done :terminal? true}])

(def ^:private on-success-pipeline
  "Pipeline with explicit :on-success override."
  [{:phase :plan}
   {:phase :implement :on-success :verify}
   {:phase :verify}])

(def ^:private standard-five-phase-pipeline
  "The canonical 5-phase software-factory pipeline: plan → implement → verify →
   review → release. Mirrors the phase keywords from defaults.edn without
   per-phase budget or on-fail overrides so the derived graph reflects pure
   structural edges only."
  [{:phase :plan}
   {:phase :implement}
   {:phase :verify}
   {:phase :review}
   {:phase :release}])

(def ^:private five-phase-node-count
  "Expected total node count for the standard 5-phase pipeline:
   5 phase nodes + 3 canonical terminals (:failed :completed :cancelled) = 8."
  8)

(def ^:private five-phase-next-edge-count
  "Expected :next edge count for the 5-phase pipeline — one per consecutive
   phase pair: plan→implement, implement→verify, verify→review, review→release."
  4)

(def ^:private five-phase-total-edge-count
  "Expected total edge count for the 5-phase pipeline with no on-fail or
   iteration-budget overrides:
     :next (4) + :on-failure (5) + :already-done (5) + :pause (5) + :cancel (5)
   = 24. No :retry, :redirect, or :on-budget-exhausted edges are emitted
   because no phase carries :on-fail or :budget {:iterations >1}."
  24)

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
;; Standard 5-phase pipeline — node set and edge count

(deftest test-default-five-phase-pipeline-nodes
  (let [g (graph/build-transition-graph standard-five-phase-pipeline)]
    (testing "standard 5-phase pipeline produces exactly the expected phase-nodes"
      (is (= #{:plan :implement :verify :review :release} (:phase-nodes g))
          "phase-nodes should match all five pipeline phases"))

    (testing "standard 5-phase pipeline node count: 5 phases + 3 canonical terminals"
      (is (= five-phase-node-count (count (:nodes g)))))

    (testing "standard 5-phase pipeline terminal-nodes equals canonical set (no :done)"
      (is (= graph/canonical-terminal-nodes (:terminal-nodes g))
          "no :done phase → terminal-nodes is exactly canonical-terminal-nodes"))))

(deftest test-default-five-phase-pipeline-next-edges
  (testing "standard 5-phase pipeline produces 4 :next edges in order"
    (let [g     (graph/build-transition-graph standard-five-phase-pipeline)
          nexts  (edges-with-label g graph/label-next)]
      (is (= five-phase-next-edge-count (count nexts)))
      (is (edge-from-to? g :plan      :implement graph/label-next))
      (is (edge-from-to? g :implement :verify    graph/label-next))
      (is (edge-from-to? g :verify    :review    graph/label-next))
      (is (edge-from-to? g :review    :release   graph/label-next))))

  (testing ":release has no :next edge (last phase)"
    (let [g (graph/build-transition-graph standard-five-phase-pipeline)
          release-nexts (filter #(and (= :release (:from %))
                                      (= graph/label-next (:label %)))
                                (:edges g))]
      (is (empty? release-nexts)))))

(deftest test-default-five-phase-pipeline-total-edge-count
  (testing "standard 5-phase pipeline without on-fail/budget overrides: 24 total edges"
    (let [g (graph/build-transition-graph standard-five-phase-pipeline)]
      (is (= five-phase-total-edge-count (count (:edges g)))
          "4 :next + 5 :on-failure + 5 :already-done + 5 :pause + 5 :cancel = 24")))

  (testing "no :retry edges on 5-phase pipeline with no iteration budget"
    (let [g (graph/build-transition-graph standard-five-phase-pipeline)]
      (is (empty? (edges-with-label g graph/label-retry)))))

  (testing "no :redirect edges on 5-phase pipeline without on-fail"
    (let [g (graph/build-transition-graph standard-five-phase-pipeline)]
      (is (empty? (edges-with-label g graph/label-redirect)))))

  (testing "no :on-budget-exhausted edges on 5-phase pipeline without on-fail"
    (let [g (graph/build-transition-graph standard-five-phase-pipeline)]
      (is (empty? (edges-with-label g graph/label-on-budget-exhausted))))))

;------------------------------------------------------------------------------ Layer 1
;; Nil pipeline — canonical anomaly (nil ≠ empty vector: distinct failure mode)

(deftest test-nil-pipeline-returns-anomaly
  (testing "nil pipeline → canonical anomaly satisfying anomaly?"
    (let [result (graph/build-transition-graph nil)]
      (is (anomaly/anomaly? result)
          "nil pipeline should return a canonical anomaly satisfying anomaly?")
      (is (= :invalid-input (:anomaly/type result)))))

  (testing "nil anomaly carries :anomaly/at timestamp"
    (let [result (graph/build-transition-graph nil)]
      (is (some? (:anomaly/at result))
          "canonical anomaly must carry :anomaly/at"))))

;------------------------------------------------------------------------------ Layer 1
;; Empty pipeline — valid graph: no phase nodes, only canonical terminal nodes

(deftest test-empty-pipeline-valid-graph
  (testing "empty vector pipeline → valid graph (not an anomaly)"
    (let [g (graph/build-transition-graph [])]
      (is (not (anomaly/anomaly? g))
          "[] should return a graph, not an anomaly")))

  (testing "empty pipeline :phase-nodes is the empty set"
    (let [g (graph/build-transition-graph [])]
      (is (= #{} (:phase-nodes g)))))

  (testing "empty pipeline :nodes contains only canonical terminals"
    (let [g (graph/build-transition-graph [])]
      (is (= graph/canonical-terminal-nodes (:nodes g)))))

  (testing "empty pipeline :terminal-nodes equals canonical-terminal-nodes"
    (let [g (graph/build-transition-graph [])]
      (is (= graph/canonical-terminal-nodes (:terminal-nodes g)))))

  (testing "empty pipeline has no edges"
    (let [g (graph/build-transition-graph [])]
      (is (= [] (:edges g))))))

;------------------------------------------------------------------------------ Layer 1
;; Malformed input — canonical anomaly/anomaly? contract

(deftest test-malformed-entry-returns-canonical-anomaly
  (testing "entry without :phase key → canonical anomaly"
    (let [result (graph/build-transition-graph [{:name :broken}])]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= 0 (get-in result [:anomaly/data :index])))))

  (testing "non-map entry → canonical anomaly"
    (let [result (graph/build-transition-graph [:not-a-map])]
      (is (anomaly/anomaly? result)))))

(deftest test-non-sequential-pipeline-returns-anomaly-not-throws
  (testing "keyword input → anomaly (not ClassCastException)"
    (let [result (graph/build-transition-graph :not-a-vector)]
      (is (anomaly/anomaly? result)
          "keyword pipeline should return anomaly, not throw")))

  (testing "integer input → anomaly (not ClassCastException)"
    (let [result (graph/build-transition-graph 42)]
      (is (anomaly/anomaly? result)
          "integer pipeline should return anomaly, not throw")))

  (testing "boolean input → anomaly (not ClassCastException)"
    (let [result (graph/build-transition-graph true)]
      (is (anomaly/anomaly? result)
          "boolean pipeline should return anomaly, not throw")))

  (testing "map input → anomaly (map is not sequential)"
    (let [result (graph/build-transition-graph {:phase :plan})]
      (is (anomaly/anomaly? result)
          "map (not vector) pipeline should return anomaly"))))

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
    (let [g         (graph/build-transition-graph minimal-pipeline)
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
    ;; :plan is not in default-budget-guarded-phases
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
;; opts / :budget-guarded-phases override

(deftest test-custom-guarded-phases-via-opts
  (testing "custom :budget-guarded-phases in opts controls redirect edge emission"
    (let [pipeline [{:phase :plan :on-fail :plan}
                    {:phase :implement}]
          ;; :plan is not in default-budget-guarded-phases, but is in custom set
          g (graph/build-transition-graph pipeline {:budget-guarded-phases #{:plan}})]
      (is (edge-from-to? g :plan :plan graph/label-redirect)
          "plan should get redirect edge when in custom guarded set")))

  (testing "empty guarded-phases set suppresses all redirect edges"
    (let [g (graph/build-transition-graph guarded-pipeline {:budget-guarded-phases #{}})]
      (is (empty? (edges-with-label g graph/label-redirect))
          "no redirect edges when guarded set is empty")
      (is (empty? (edges-with-label g graph/label-on-budget-exhausted))
          "no budget-exhausted edges when guarded set is empty"))))

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
  (testing "explicit :on-success to adjacent phase — :next edge present, no separate :on-success"
    (let [g (graph/build-transition-graph on-success-pipeline)]
      ;; :implement → :verify via :next (on-success-pipeline: implement at 1, verify at 2)
      ;; since on-success target equals next-index, no separate :on-success edge is emitted
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

;------------------------------------------------------------------------------ Layer 1
;; Duplicate :phase keyword detection
;; Structural invariant: graph-node identifiers must be unique. Duplicate :phase
;; keywords produce ambiguous edge routing — the same node would receive multiple
;; conflicting :next edges and an indeterminate :already-done target — so the
;; derivation rejects them before graph construction.

(deftest test-duplicate-phase-names-rejected-as-ambiguous
  (testing "two entries sharing the same :phase keyword → canonical anomaly"
    (let [result (graph/build-transition-graph [{:phase :plan} {:phase :plan}])]
      (is (anomaly/anomaly? result)
          "duplicate :phase keywords should return a canonical anomaly satisfying anomaly?")
      (is (= :invalid-input (:anomaly/type result)))
      (is (contains? (set (get-in result [:anomaly/data :duplicate-phases])) :plan)
          ":duplicate-phases data should name the offending keyword")))

  (testing "three entries two of which share :phase :review → :review in duplicate set"
    (let [result (graph/build-transition-graph
                  [{:phase :plan}
                   {:phase :review}
                   {:phase :review}])]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (contains? (set (get-in result [:anomaly/data :duplicate-phases])) :review))))

  (testing "multiple distinct duplicates all reported"
    (let [result (graph/build-transition-graph
                  [{:phase :plan}
                   {:phase :plan}
                   {:phase :verify}
                   {:phase :verify}])]
      (is (anomaly/anomaly? result))
      (let [dupes (set (get-in result [:anomaly/data :duplicate-phases]))]
        (is (contains? dupes :plan))
        (is (contains? dupes :verify)))))

  (testing "malformed entry takes precedence over duplicate detection"
    ;; per-entry-error runs before duplicate-phase-error in validate-pipeline
    (let [result (graph/build-transition-graph
                  [{:name :bad-entry}
                   {:phase :plan}
                   {:phase :plan}])]
      (is (anomaly/anomaly? result))
      (is (= 0 (get-in result [:anomaly/data :index]))
          "per-entry error at index 0 should be returned, not a duplicate-phases anomaly"))))

(comment
  ;; Minimal valid pipeline — one phase
  (graph/build-transition-graph [{:phase :plan}])
  ;; => {:nodes #{:plan :failed :completed :cancelled}
  ;;     :edges [... 4 edges: :on-failure :already-done :pause :cancel]
  ;;     :terminal-nodes #{:failed :completed :cancelled}
  ;;     :phase-nodes #{:plan}}

  ;; Empty pipeline — valid, returns only canonical terminal nodes
  (graph/build-transition-graph [])
  ;; => {:nodes #{:failed :completed :cancelled}
  ;;     :edges []
  ;;     :terminal-nodes #{:failed :completed :cancelled}
  ;;     :phase-nodes #{}}

  ;; Standard 5-phase pipeline — 24 edges total
  (graph/build-transition-graph
   [{:phase :plan}
    {:phase :implement}
    {:phase :verify}
    {:phase :review}
    {:phase :release}])
  ;; => {:nodes #{:plan :implement :verify :review :release :failed :completed :cancelled}
  ;;     :edges [...24...] ...}

  ;; Duplicate :phase → anomaly (ambiguous graph routing)
  (graph/build-transition-graph [{:phase :plan} {:phase :plan}])
  ;; => {:anomaly/type :invalid-input
  ;;     :anomaly/data {:duplicate-phases [:plan]} ...}
  )
