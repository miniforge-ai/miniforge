(ns ai.miniforge.phase.transition-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.miniforge.phase.transition-graph :as tg]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories and fixtures

(defn- simple-graph
  "Build a minimal two-node graph: entry → terminal."
  []
  (tg/graph :entry-node
            [(tg/node :entry-node :plan 0 false)
             (tg/terminal-node :completed 1)]
            [(tg/edge :entry-node :completed tg/edge-next)]))

(defn- guarded-graph
  "Build a graph with a guarded retry loop."
  []
  (tg/graph :implement-node
            [(tg/node :implement-node :implement 0 false)
             (tg/terminal-node :completed 1)
             (tg/terminal-node :failed 2)]
            [(tg/edge :implement-node :completed tg/edge-on-success)
             (tg/edge :implement-node :implement-node tg/edge-retry :implement/retry-allowed?)
             (tg/edge :implement-node :failed tg/edge-terminal-verdict)]))

;------------------------------------------------------------------------------ Layer 1
;; Edge-label constant tests

(deftest test-edge-labels-are-keywords
  (testing "All edge-label constants are namespaced keywords"
    (is (= :edge/next            tg/edge-next))
    (is (= :edge/on-failure      tg/edge-on-failure))
    (is (= :edge/on-success      tg/edge-on-success))
    (is (= :edge/retry           tg/edge-retry))
    (is (= :edge/already-done    tg/edge-already-done))
    (is (= :edge/terminal-verdict tg/edge-terminal-verdict))
    (is (= :edge/budget-exhausted tg/edge-budget-exhausted))))

(deftest test-all-edge-labels-set-completeness
  (testing "all-edge-labels contains all seven label constants"
    (is (= 7 (count tg/all-edge-labels)))
    (is (every? tg/all-edge-labels
                [tg/edge-next
                 tg/edge-on-failure
                 tg/edge-on-success
                 tg/edge-retry
                 tg/edge-already-done
                 tg/edge-terminal-verdict
                 tg/edge-budget-exhausted]))))

(deftest test-terminal-states-set
  (testing "terminal-states contains exactly the expected values"
    (is (= #{:completed :failed :cancelled} tg/terminal-states))))

(deftest test-guarded-phases-set
  (testing "guarded-phases mirrors workflow.fsm/guarded-phases (Phase 3c set)"
    (is (= #{:review :verify :release :implement} tg/guarded-phases))))

;; ── Schema validation ────────────────────────────────────────────────────────

(deftest test-edge-label-schema-accepts-valid-labels
  (testing "EdgeLabel schema validates each known label"
    (is (every? #(m/validate tg/EdgeLabel %) tg/all-edge-labels))))

(deftest test-edge-label-schema-rejects-unknown-labels
  (testing "EdgeLabel schema rejects arbitrary keywords"
    (is (false? (m/validate tg/EdgeLabel :edge/made-up)))
    (is (false? (m/validate tg/EdgeLabel :phase/next)))))

(deftest test-node-schema-validates-factory-output
  (testing "NodeSchema validates nodes built by node factory"
    (is (m/validate tg/NodeSchema (tg/node :plan-node :plan 0 false)))
    (is (m/validate tg/NodeSchema (tg/terminal-node :completed 1)))))

(deftest test-node-schema-rejects-missing-fields
  (testing "NodeSchema rejects incomplete node maps"
    (is (false? (m/validate tg/NodeSchema {:node/id :x :node/phase :plan})))))

(deftest test-edge-schema-validates-unconditional-edge
  (testing "EdgeSchema validates an unconditional (3-arity) edge"
    (is (m/validate tg/EdgeSchema
                    (tg/edge :plan-node :implement-node tg/edge-next)))))

(deftest test-edge-schema-validates-guarded-edge
  (testing "EdgeSchema validates a guarded (4-arity) edge"
    (is (m/validate tg/EdgeSchema
                    (tg/edge :impl :impl tg/edge-retry :impl/retry-allowed?)))))

(deftest test-edge-schema-rejects-unknown-label
  (testing "EdgeSchema rejects an edge with an invalid label"
    (is (false? (m/validate tg/EdgeSchema
                            {:edge/from  :a
                             :edge/to    :b
                             :edge/label :edge/not-a-real-label})))))

(deftest test-graph-schema-validates-simple-graph
  (testing "GraphSchema validates a minimal well-formed graph"
    (is (m/validate tg/GraphSchema (simple-graph)))))

(deftest test-graph-schema-validates-guarded-graph
  (testing "GraphSchema validates a graph with retry and guard edges"
    (is (m/validate tg/GraphSchema (guarded-graph)))))

;; ── Factory function tests ────────────────────────────────────────────────────

(deftest test-node-factory-sets-all-fields
  (testing "node factory produces a map with all required keys"
    (let [n (tg/node :plan-node :plan 2 false)]
      (is (= :plan-node (:node/id n)))
      (is (= :plan (:node/phase n)))
      (is (= 2 (:node/index n)))
      (is (false? (:node/terminal? n))))))

(deftest test-terminal-node-factory-two-arity
  (testing "terminal-node 2-arity uses phase as id"
    (let [n (tg/terminal-node :completed 5)]
      (is (= :completed (:node/id n)))
      (is (= :completed (:node/phase n)))
      (is (= 5 (:node/index n)))
      (is (true? (:node/terminal? n))))))

(deftest test-terminal-node-factory-three-arity
  (testing "terminal-node 3-arity uses explicit id"
    (let [n (tg/terminal-node :budget-exit :cancelled 6)]
      (is (= :budget-exit (:node/id n)))
      (is (= :cancelled (:node/phase n)))
      (is (true? (:node/terminal? n))))))

(deftest test-edge-factory-three-arity
  (testing "edge 3-arity produces map without :edge/guard"
    (let [e (tg/edge :a :b tg/edge-next)]
      (is (= :a (:edge/from e)))
      (is (= :b (:edge/to e)))
      (is (= tg/edge-next (:edge/label e)))
      (is (nil? (:edge/guard e))))))

(deftest test-edge-factory-four-arity
  (testing "edge 4-arity includes :edge/guard"
    (let [e (tg/edge :a :a tg/edge-retry :some-guard)]
      (is (= :some-guard (:edge/guard e))))))

(deftest test-graph-factory-wraps-nodes-and-edges
  (testing "graph factory produces correct envelope shape"
    (let [g (simple-graph)]
      (is (= :entry-node (:graph/entry g)))
      (is (= 2 (count (:graph/nodes g))))
      (is (= 1 (count (:graph/edges g)))))))

(deftest test-graph-factory-coerces-to-vectors
  (testing "graph factory converts lazy seqs to vectors"
    (let [nodes (list (tg/node :a :plan 0 false))
          edges (list (tg/edge :a :a tg/edge-retry))
          g     (tg/graph :a nodes edges)]
      (is (vector? (:graph/nodes g)))
      (is (vector? (:graph/edges g))))))
