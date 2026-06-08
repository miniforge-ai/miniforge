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

(ns ai.miniforge.phase.graph-validator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph :as graph]
   [ai.miniforge.phase.graph-validator :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Shared constants and factories

(def ^:private sdlc-default-pipeline
  "Canonical miniforge SDLC pipeline shape — mirrors the shipped default workflow
   configuration (same shape as graph-test.clj's guarded-pipeline and the
   graph.clj REPL comment block). Regression pin: this pipeline must produce a
   TransitionGraph that passes all six non-interceptor structural checks.

   Key-format note: :budget {:iterations N} is the pipeline-config format; the
   graph builder reads (get-in cfg [:budget :iterations]) and propagates the
   value to the retry edge :meta under :iterations for graph-validator checks.
   See graph/retry-edges and graph-validator/check-retry-bounds."
  [{:phase :plan}
   {:phase :implement :budget {:iterations 3} :on-fail :plan}
   {:phase :verify    :on-fail :implement}
   {:phase :review    :on-fail :implement}
   {:phase :done :terminal? true}])

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories
;;
;; All graph fixtures are plain maps. Each has the four required keys:
;;   :nodes          — set of all node keywords
;;   :edges          — vector of edge maps {:from :to :label :meta}
;;   :terminal-nodes — set of terminal keywords (subset of :nodes)
;;   :phase-nodes    — set of phase keyword (subset of non-terminal :nodes)

(def ^:private empty-graph
  "Graph with no nodes, edges, or terminals. Valid — trivially passes all checks."
  {:nodes          #{}
   :edges          []
   :terminal-nodes #{}
   :phase-nodes    #{}})

(def ^:private terminal-only-graph
  "Graph with only terminal nodes. No non-terminal nodes to fail any checks."
  {:nodes          #{:done :failed}
   :edges          []
   :terminal-nodes #{:done :failed}
   :phase-nodes    #{}})

(def ^:private minimal-valid-graph
  "Simplest valid graph: two phases, two terminals, success + failure paths."
  {:nodes          #{:plan :implement :done :failed}
   :edges          [{:from :plan      :to :implement :label :next        :meta {}}
                    {:from :plan      :to :failed    :label :on-failure  :meta {}}
                    {:from :implement :to :done      :label :next        :meta {}}
                    {:from :implement :to :failed    :label :on-failure  :meta {}}]
   :terminal-nodes #{:done :failed}
   :phase-nodes    #{:plan :implement}})

(defn- make-edge
  "Build a single edge map. `meta` defaults to empty map."
  ([from to label] (make-edge from to label {}))
  ([from to label meta] {:from from :to to :label label :meta meta}))

(def ^:private test-retry-iteration-budget
  "Iteration count used in retry-bounds test fixtures.
   Three iterations: enough to exercise the guarded-retry path, small enough
   to remain clearly test-scoped rather than production-like. Matches the
   count used in graph-test.clj's budgeted-pipeline and guarded-pipeline
   fixtures to confirm both are reading the same :budget {:iterations N} key."
  3)

(defn- anomaly-data
  "Extract :anomaly/data from an anomaly map for structural assertions."
  [a]
  (:anomaly/data a))

;------------------------------------------------------------------------------ Layer 1
;; Property 1 — Existence

(deftest check-existence-happy-path
  (testing "all edge targets exist in :nodes → no anomalies"
    (is (empty? (sut/check-existence minimal-valid-graph))))
  (testing "empty graph has no edges → no anomalies"
    (is (empty? (sut/check-existence empty-graph))))
  (testing "terminal-only graph has no edges → no anomalies"
    (is (empty? (sut/check-existence terminal-only-graph)))))

(deftest check-existence-dangling-edge
  (testing "edge to a node not in :nodes → one anomaly per dangling target"
    (let [graph  {:nodes          #{:plan :failed}
                  :edges          [(make-edge :plan :missing-phase :next)
                                   (make-edge :plan :failed :on-failure)]
                  :terminal-nodes #{:failed}
                  :phase-nodes    #{:plan}}
          result (sut/check-existence graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :missing-phase (:missing-node (anomaly-data (first result)))))))
  (testing "two dangling edges → two anomalies"
    (let [graph  {:nodes          #{:plan}
                  :edges          [(make-edge :plan :ghost-a :next)
                                   (make-edge :plan :ghost-b :on-failure)]
                  :terminal-nodes #{}
                  :phase-nodes    #{:plan}}
          result (sut/check-existence graph)]
      (is (= 2 (count result)))
      (is (= #{:ghost-a :ghost-b}
             (set (map (comp :missing-node anomaly-data) result)))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 2 — Termination

(deftest check-termination-happy-path
  (testing "valid graph → all non-terminal nodes reach a terminal"
    (is (empty? (sut/check-termination minimal-valid-graph))))
  (testing "empty graph → no nodes to check → no anomalies"
    (is (empty? (sut/check-termination empty-graph))))
  (testing "terminal-only graph → no non-terminal nodes → no anomalies"
    (is (empty? (sut/check-termination terminal-only-graph)))))

(deftest check-termination-unreachable-node
  (testing "isolated non-terminal node with no outgoing edges → anomaly"
    (let [graph  {:nodes          #{:plan :stranded :done}
                  :edges          [(make-edge :plan :done :next)
                                   (make-edge :plan :done :on-failure)]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan :stranded}}
          result (sut/check-termination graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :stranded (:node (anomaly-data (first result)))))))
  (testing "node with outgoing edge only to another non-terminal with no terminal path → anomaly"
    (let [graph  {:nodes          #{:a :b :done}
                  :edges          [(make-edge :a :b :next)
                                   ;; :b has no path to terminal
                                   ]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:a :b}}
          result (sut/check-termination graph)]
      ;; both :a (indirect) and :b (direct) have no terminal path
      (is (= 2 (count result))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 3 — Cycles

(deftest check-cycles-no-cycle
  (testing "linear graph without cycles → no anomalies"
    (is (empty? (sut/check-cycles minimal-valid-graph))))
  (testing "empty graph → no anomalies"
    (is (empty? (sut/check-cycles empty-graph)))))

(deftest check-cycles-runaway-cycle
  (testing "cycle with no retry-bounded node → anomaly"
    (let [graph  {:nodes          #{:plan :implement :done :failed}
                  :edges          [(make-edge :plan      :implement :next)
                                   (make-edge :implement :plan      :redirect)
                                   (make-edge :plan      :failed    :on-failure)
                                   (make-edge :implement :failed    :on-failure)]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:plan :implement}}
          result (sut/check-cycles graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (let [cycle-nodes (:cycle-nodes (anomaly-data (first result)))]
        (is (= #{:plan :implement} cycle-nodes))))))

(deftest check-cycles-allowed-retry-self-loop
  (testing "self-loop via :retry label on same node → NOT a runaway (retry node present)"
    (let [graph  {:nodes          #{:implement :done :failed}
                  :edges          [(make-edge :implement :implement :retry)
                                   (make-edge :implement :done      :next)
                                   (make-edge :implement :failed    :on-budget-exhausted)]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement}}
          result (sut/check-cycles graph)]
      ;; Self-loop detected as a cycle BUT allowed because :implement has a :retry edge
      (is (empty? result))))
  (testing "multi-node cycle where at least one node has a :retry self-loop → allowed"
    (let [graph  {:nodes          #{:plan :implement :done :failed}
                  :edges          [(make-edge :plan      :implement :next)
                                   (make-edge :implement :plan      :redirect)
                                   (make-edge :implement :implement :retry)
                                   (make-edge :plan      :failed    :on-failure)
                                   (make-edge :implement :failed    :on-budget-exhausted)]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:plan :implement}}
          result (sut/check-cycles graph)]
      (is (empty? result)))))

;------------------------------------------------------------------------------ Layer 1
;; Property 4 — Retry Bounds

(deftest check-retry-bounds-happy-path
  (testing "retry with :iterations meta AND exhaustion edge to terminal → no anomalies"
    (let [graph  {:nodes          #{:implement :done :failed}
                  :edges          [(make-edge :implement :implement :retry   {:iterations test-retry-iteration-budget})
                                   (make-edge :implement :done      :next    {})
                                   (make-edge :implement :failed    :on-budget-exhausted {})]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement}}
          result (sut/check-retry-bounds graph)]
      (is (empty? result))))
  (testing "no retry edges in graph → no anomalies"
    (is (empty? (sut/check-retry-bounds minimal-valid-graph))))
  (testing "empty graph → no anomalies"
    (is (empty? (sut/check-retry-bounds empty-graph)))))

(deftest check-retry-bounds-missing-iterations
  (testing "retry self-loop without :iterations in :meta → anomaly"
    (let [graph  {:nodes          #{:implement :done :failed}
                  :edges          [(make-edge :implement :implement :retry   {})   ;; no :iterations
                                   (make-edge :implement :done      :next    {})
                                   (make-edge :implement :failed    :on-budget-exhausted {})]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement}}
          result (sut/check-retry-bounds graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :implement (:node (anomaly-data (first result))))))))

(deftest check-retry-bounds-missing-exhaustion-path
  (testing "retry with :iterations but no :on-budget-exhausted edge → anomaly"
    (let [graph  {:nodes          #{:implement :done}
                  :edges          [(make-edge :implement :implement :retry {:iterations test-retry-iteration-budget})
                                   (make-edge :implement :done      :next  {})]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:implement}}
          result (sut/check-retry-bounds graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))))
  (testing "retry with :iterations AND exhaustion edge but exhaustion target is non-terminal → anomaly"
    (let [graph  {:nodes          #{:implement :stranded :done}
                  :edges          [(make-edge :implement :implement :retry             {:iterations test-retry-iteration-budget})
                                   (make-edge :implement :done      :next              {})
                                   (make-edge :implement :stranded  :on-budget-exhausted {})]
                  ;; :stranded is NOT in terminal-nodes and has no outgoing edge to terminal
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:implement :stranded}}
          result (sut/check-retry-bounds graph)]
      ;; :stranded doesn't reach terminal → exhaustion path anomaly
      (is (pos? (count result))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 5 — Failure Paths

(deftest check-failure-paths-happy-path
  (testing "all phase nodes have :on-failure edge to terminal → no anomalies"
    (is (empty? (sut/check-failure-paths minimal-valid-graph))))
  (testing "empty graph → no anomalies"
    (is (empty? (sut/check-failure-paths empty-graph))))
  (testing "terminal-only graph → no non-terminal phase nodes → no anomalies"
    (is (empty? (sut/check-failure-paths terminal-only-graph))))
  (testing ":on-budget-exhausted edge counts as a failure path"
    (let [graph  {:nodes          #{:implement :done :failed}
                  :edges          [(make-edge :implement :done   :next              {})
                                   (make-edge :implement :failed :on-budget-exhausted {})]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement}}]
      (is (empty? (sut/check-failure-paths graph)))))
  (testing ":redirect edge counts as a failure path"
    (let [graph  {:nodes          #{:implement :plan :done :failed}
                  :edges          [(make-edge :implement :done   :next     {})
                                   (make-edge :implement :plan   :redirect {})
                                   (make-edge :plan      :done   :next     {})
                                   (make-edge :plan      :failed :on-failure {})]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement :plan}}]
      (is (empty? (sut/check-failure-paths graph))))))

(deftest check-failure-paths-missing-failure-path
  (testing "phase node with only a :next edge — no failure path → anomaly"
    (let [graph  {:nodes          #{:plan :done}
                  :edges          [(make-edge :plan :done :next {})]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          result (sut/check-failure-paths graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :plan (:node (anomaly-data (first result)))))))
  (testing "failure edge that leads to a non-terminal with no further path → anomaly"
    (let [graph  {:nodes          #{:implement :stranded :done}
                  :edges          [(make-edge :implement :done      :next       {})
                                   (make-edge :implement :stranded  :on-failure {})]
                  ;; :stranded is not terminal and has no outgoing edges
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:implement :stranded}}
          result (sut/check-failure-paths graph)]
      ;; :implement's failure path leads to :stranded which doesn't reach terminal
      (is (pos? (count result))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 6 — Budget Paths

(deftest check-budget-paths-happy-path
  (testing "no budget-guarded nodes → no anomalies"
    (is (empty? (sut/check-budget-paths minimal-valid-graph))))
  (testing "empty graph → no anomalies"
    (is (empty? (sut/check-budget-paths empty-graph))))
  (testing "budget-guarded node with :on-budget-exhausted edge to terminal → no anomalies"
    (let [graph  {:nodes          #{:implement :done :failed}
                  :edges          [(make-edge :implement :done   :next              {:budget-guarded? true})
                                   (make-edge :implement :failed :on-budget-exhausted {})]
                  :terminal-nodes #{:done :failed}
                  :phase-nodes    #{:implement}}]
      (is (empty? (sut/check-budget-paths graph))))))

(deftest check-budget-paths-missing-exhausted-edge
  (testing "budget-guarded node with no :on-budget-exhausted edge → anomaly"
    (let [graph  {:nodes          #{:implement :done}
                  :edges          [(make-edge :implement :done :next {:budget-guarded? true})]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:implement}}
          result (sut/check-budget-paths graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :implement (:node (anomaly-data (first result))))))))

(deftest check-budget-paths-exhausted-edge-no-terminal
  (testing "budget-guarded node with :on-budget-exhausted edge not reaching terminal → anomaly"
    (let [graph  {:nodes          #{:implement :stranded :done}
                  :edges          [(make-edge :implement :done      :next              {:budget-guarded? true})
                                   (make-edge :implement :stranded  :on-budget-exhausted {})]
                  ;; :stranded is not terminal and has no path to terminal
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:implement :stranded}}
          result (sut/check-budget-paths graph)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :implement (:node (anomaly-data (first result))))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 7 — Interceptors (opt-in)

(deftest check-interceptors-opt-out
  (testing "no opts → check skipped → empty result"
    (is (empty? (sut/check-interceptors minimal-valid-graph {}))))
  (testing "check-interceptors? false → check skipped → empty result"
    (is (empty? (sut/check-interceptors minimal-valid-graph
                                        {:check-interceptors? false}))))
  (testing "check-interceptors? true but no fn provided → check skipped → empty result"
    (is (empty? (sut/check-interceptors minimal-valid-graph
                                        {:check-interceptors? true})))))

(deftest check-interceptors-opt-in-happy-path
  (testing "check-interceptors? true, all phases registered → no anomalies"
    (let [graph  {:nodes          #{:plan :implement :done}
                  :edges          []
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan :implement}}
          opts   {:check-interceptors?       true
                  :interceptor-registered-fn #{:plan :implement}}
          result (sut/check-interceptors graph opts)]
      (is (empty? result))))
  (testing "empty :phase-nodes → nothing to check → no anomalies"
    (is (empty? (sut/check-interceptors terminal-only-graph
                                        {:check-interceptors?       true
                                         :interceptor-registered-fn (constantly true)})))))

(deftest check-interceptors-unregistered-phase
  (testing "check-interceptors? true, one phase unregistered → anomaly"
    (let [graph  {:nodes          #{:plan :implement :done}
                  :edges          []
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan :implement}}
          opts   {:check-interceptors?       true
                  :interceptor-registered-fn #{:plan}}  ;; :implement missing
          result (sut/check-interceptors graph opts)]
      (is (= 1 (count result)))
      (is (every? anomaly/anomaly? result))
      (is (= :implement (:phase (anomaly-data (first result)))))))
  (testing "no phases registered → anomaly per phase"
    (let [graph  {:nodes          #{:plan :implement :done}
                  :edges          []
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan :implement}}
          opts   {:check-interceptors?       true
                  :interceptor-registered-fn (constantly false)}
          result (sut/check-interceptors graph opts)]
      (is (= 2 (count result))))))

;------------------------------------------------------------------------------ Layer 2
;; Aggregate — validate-graph + valid?

(deftest validate-graph-valid
  (testing "valid graph → {:valid? true :errors [] :warnings []}"
    (let [result (sut/validate-graph minimal-valid-graph)]
      (is (true?  (:valid? result)))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))))
  (testing "empty graph → valid (no constraints to violate)"
    (let [result (sut/validate-graph empty-graph)]
      (is (true?  (:valid? result)))
      (is (empty? (:errors result)))))
  (testing "terminal-only graph → valid"
    (let [result (sut/validate-graph terminal-only-graph)]
      (is (true?  (:valid? result))))))

(deftest validate-graph-invalid
  (testing "graph with dangling edge → :valid? false, anomaly in :errors"
    (let [graph  {:nodes          #{:plan :done}
                  :edges          [(make-edge :plan :ghost :next)
                                   (make-edge :plan :done  :on-failure)]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          result (sut/validate-graph graph)]
      (is (false? (:valid? result)))
      (is (pos?   (count (:errors result))))
      (is (empty? (:warnings result)))))
  (testing "graph with multiple property violations → all appear in :errors"
    (let [graph  {:nodes          #{:plan :done}
                  :edges          [(make-edge :plan :ghost :next)]  ;; dangling + no failure path
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          result (sut/validate-graph graph)]
      (is (false? (:valid? result)))
      (is (>= (count (:errors result)) 2))))
  (testing "opts forwarded to check-interceptors — unregistered interceptor included in errors"
    (let [graph  {:nodes          #{:plan :done}
                  :edges          [(make-edge :plan :done :next)
                                   (make-edge :plan :done :on-failure)]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          opts   {:check-interceptors?       true
                  :interceptor-registered-fn (constantly false)}
          result (sut/validate-graph graph opts)]
      (is (false? (:valid? result)))
      (is (some #(= :plan (:phase (anomaly-data %))) (:errors result))))))

(deftest valid?-convenience
  (testing "valid graph → true"
    (is (true?  (sut/valid? minimal-valid-graph))))
  (testing "empty graph → true"
    (is (true?  (sut/valid? empty-graph))))
  (testing "terminal-only graph → true"
    (is (true?  (sut/valid? terminal-only-graph))))
  (testing "graph with dangling edge → false"
    (let [graph {:nodes          #{:plan :done}
                 :edges          [(make-edge :plan :ghost :next)
                                  (make-edge :plan :done  :on-failure)]
                 :terminal-nodes #{:done}
                 :phase-nodes    #{:plan}}]
      (is (false? (sut/valid? graph))))))

;------------------------------------------------------------------------------ Layer 2
;; Anomaly shape — rule 005 conformance

(deftest anomaly-maps-match-rule-005-shape
  (testing "every anomaly produced by any check has the four canonical rule-005 keys"
    ;; Use check-existence — simplest to trigger with a dangling edge.
    (let [graph  {:nodes          #{:plan :done}
                  :edges          [(make-edge :plan :ghost :next)
                                   (make-edge :plan :done  :on-failure)]
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          a      (first (sut/check-existence graph))]
      (is (some? (:anomaly/type    a)) ":anomaly/type must be present")
      (is (some? (:anomaly/message a)) ":anomaly/message must be present")
      (is (map?  (:anomaly/data    a)) ":anomaly/data must be a map")
      (is (some? (:anomaly/at      a)) ":anomaly/at must be present")
      (is (anomaly/anomaly? a)         "anomaly/anomaly? must recognise it")))
  (testing "check-termination anomaly also conforms to rule 005"
    (let [graph  {:nodes          #{:stranded :done}
                  :edges          []
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:stranded}}
          a      (first (sut/check-termination graph))]
      (is (keyword? (:anomaly/type a)))
      (is (string?  (:anomaly/message a)))
      (is (map?     (:anomaly/data a)))
      (is (some?    (:anomaly/at a)))))
  (testing "check-interceptors anomaly conforms to rule 005"
    (let [graph  {:nodes          #{:plan :done}
                  :edges          []
                  :terminal-nodes #{:done}
                  :phase-nodes    #{:plan}}
          opts   {:check-interceptors?       true
                  :interceptor-registered-fn (constantly false)}
          a      (first (sut/check-interceptors graph opts))]
      (is (keyword? (:anomaly/type a)))
      (is (string?  (:anomaly/message a)))
      (is (map?     (:anomaly/data a)))
      (is (some?    (:anomaly/at a))))))

;------------------------------------------------------------------------------ Layer 2
;; Regression — shipped default pipeline

(deftest default-pipeline-passes-all-structural-checks
  (testing "shipped SDLC default pipeline builds without error"
    (let [graph-or-anomaly (graph/build-transition-graph sdlc-default-pipeline)]
      (is (not (anomaly/anomaly? graph-or-anomaly))
          (str "build-transition-graph returned anomaly: " graph-or-anomaly))))
  (testing "shipped SDLC default pipeline passes all 6 non-interceptor checks"
    (let [tg     (graph/build-transition-graph sdlc-default-pipeline)
          result (sut/validate-graph tg)]
      (is (true?  (:valid? result))
          (str "expected :valid? true; errors: " (mapv :anomaly/message (:errors result))))
      (is (empty? (:errors   result)))
      (is (empty? (:warnings result)))))
  (testing "property 1 — no dangling edges in default pipeline graph"
    (is (empty? (sut/check-existence (graph/build-transition-graph sdlc-default-pipeline)))))
  (testing "property 2 — every node reaches terminal in default pipeline graph"
    (is (empty? (sut/check-termination (graph/build-transition-graph sdlc-default-pipeline)))))
  (testing "property 5 — every phase has a failure path in default pipeline graph"
    (is (empty? (sut/check-failure-paths (graph/build-transition-graph sdlc-default-pipeline))))))

(comment
  ;; Quick REPL smoke-tests for this test namespace
  ;; Integration tests live in graph-validator-integration-test.
  (require '[clojure.test :as t])
  (t/run-tests 'ai.miniforge.phase.graph-validator-test)
  )
