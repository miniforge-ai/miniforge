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

(ns ai.miniforge.algorithms.graph-test
  "Tests for core DFS traversal in ai.miniforge.algorithms.graph."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.graph :as graph]))

;------------------------------------------------------------------------------ Test Fixtures

(def get-deps :deps)

(def empty-graph {})

(def single-node
  {"a" {:id "a" :deps []}})

(def linear-chain
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def diamond-dag
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["d"]}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def forest
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps []}
   "c" {:id "c" :deps ["d"]}
   "d" {:id "d" :deps []}})

(def self-cycle
  {"a" {:id "a" :deps ["a"]}})

(def direct-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["a"]}})

(def indirect-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

(def missing-dep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["missing"]}})

;; Default no-op callbacks
(def noop-visit   (fn [_id _node _path _visited _visiting] nil))
(def noop-cycle   (fn [_id _path _visited _visiting] nil))
(def noop-missing (fn [_id _visited _visiting] nil))

;------------------------------------------------------------------------------ Layer 0
;; Core DFS traversal tests

(deftest dfs-empty-graph-test
  (testing "empty graph with empty start-ids returns empty visited and nil"
    (let [[visited result] (graph/dfs empty-graph [] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{} visited))
      (is (nil? result))))

  (testing "empty graph with start-id calls on-missing-fn"
    (let [missing-calls (atom [])
          [visited result] (graph/dfs empty-graph ["a"] get-deps
                                      noop-visit
                                      noop-cycle
                                      (fn [node-id _visited _visiting]
                                        (swap! missing-calls conj node-id)
                                        {:missing node-id}))]
      (is (= ["a"] @missing-calls))
      (is (= {:missing "a"} result))
      (is (= #{} visited)))))

(deftest dfs-single-node-test
  (testing "single node with no deps visits it and returns nil"
    (let [[visited result] (graph/dfs single-node ["a"] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result))))

  (testing "on-visit-fn receives correct arguments"
    (let [visit-args (atom [])
          [_visited _result] (graph/dfs single-node ["a"] get-deps
                                        (fn [node-id node path visited visiting]
                                          (swap! visit-args conj
                                                 {:id node-id :node node
                                                  :path path :visited visited
                                                  :visiting visiting})
                                          nil)
                                        noop-cycle noop-missing)
          args (first @visit-args)]
      (is (= 1 (count @visit-args)))
      (is (= "a" (:id args)))
      (is (= {:id "a" :deps []} (:node args)))
      (is (= [] (:path args)) "path at root node is empty (path-to, not path-including)")
      (is (set? (:visited args)))
      (is (set? (:visiting args))))))

(deftest dfs-linear-chain-test
  (testing "traverses a->b->c->d depth-first, visits all 4 nodes"
    (let [[visited result] (graph/dfs linear-chain ["a"] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result))))

  (testing "path grows correctly along the chain"
    (let [paths (atom {})
          [_visited _result] (graph/dfs linear-chain ["a"] get-deps
                                        (fn [node-id _node path _visited _visiting]
                                          (swap! paths assoc node-id path)
                                          nil)
                                        noop-cycle noop-missing)]
      (is (= [] (get @paths "a")) "root has empty path-to")
      (is (= ["a" "b" "c"] (get @paths "d"))))))

(deftest dfs-diamond-dag-test
  (testing "diamond a->{b,c}->d: d visited only once, all 4 in visited set"
    (let [visit-counts (atom {})
          [visited result] (graph/dfs diamond-dag ["a"] get-deps
                                      (fn [node-id _node _path _visited _visiting]
                                        (swap! visit-counts update node-id (fnil inc 0))
                                        nil)
                                      noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result))
      (is (= 1 (get @visit-counts "d"))
          "d should be visited exactly once despite two paths leading to it")
      (is (= 4 (count @visit-counts))))))

(deftest dfs-forest-disconnected-test
  (testing "two disconnected trees with start-ids [a c] visits all nodes"
    (let [[visited result] (graph/dfs forest ["a" "c"] get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))
      (is (nil? result))))

  (testing "visiting resets between start nodes (visiting set is fresh)"
    (let [visiting-snapshots (atom {})
          [_visited _result] (graph/dfs forest ["a" "c"] get-deps
                                        (fn [node-id _node _path _visited visiting]
                                          (swap! visiting-snapshots assoc node-id visiting)
                                          nil)
                                        noop-cycle noop-missing)]
      (is (not (contains? (get @visiting-snapshots "c") "a"))
          "visiting set resets between start nodes"))))

(deftest dfs-halt-on-visit-test
  (testing "on-visit-fn returning non-nil halts traversal"
    (let [[visited result] (graph/dfs linear-chain ["a"] get-deps
                                      (fn [node-id _node _path _visited _visiting]
                                        (when (= node-id "c")
                                          {:halted-at "c"}))
                                      noop-cycle noop-missing)]
      (is (= {:halted-at "c"} result))
      (is (not (contains? visited "c")))
      (is (not (contains? visited "d"))))))

(deftest dfs-cycle-detection-test
  (testing "self-cycle: on-cycle-fn called with correct path"
    (let [cycle-calls (atom [])
          [_visited result] (graph/dfs self-cycle ["a"] get-deps
                                       noop-visit
                                       (fn [node-id path _visited _visiting]
                                         (swap! cycle-calls conj {:id node-id :path path})
                                         {:cycle node-id})
                                       noop-missing)]
      (is (= 1 (count @cycle-calls)))
      (is (= "a" (:id (first @cycle-calls))))
      (is (= ["a" "a"] (:path (first @cycle-calls))))
      (is (= {:cycle "a"} result))))

  (testing "direct cycle a->b->a: on-cycle-fn called"
    (let [cycle-calls (atom [])
          [_visited result] (graph/dfs direct-cycle ["a"] get-deps
                                       noop-visit
                                       (fn [node-id path _visited _visiting]
                                         (swap! cycle-calls conj {:id node-id :path path})
                                         {:cycle node-id})
                                       noop-missing)]
      (is (= 1 (count @cycle-calls)))
      (is (= "a" (:id (first @cycle-calls))))
      (is (some #(= "a" %) (:path (first @cycle-calls))))
      (is (some? result))))

  (testing "indirect cycle a->b->c->a: on-cycle-fn called with full path"
    (let [cycle-calls (atom [])
          [_visited result] (graph/dfs indirect-cycle ["a"] get-deps
                                       noop-visit
                                       (fn [node-id path _visited _visiting]
                                         (swap! cycle-calls conj {:id node-id :path path})
                                         {:cycle node-id})
                                       noop-missing)]
      (is (= 1 (count @cycle-calls)))
      (is (= "a" (:id (first @cycle-calls))))
      (is (= ["a" "b" "c" "a"] (:path (first @cycle-calls))))
      (is (= {:cycle "a"} result)))))

(deftest dfs-cycle-continue-test
  (testing "on-cycle-fn returning nil continues traversal"
    (let [cycle-count (atom 0)
          [visited result] (graph/dfs direct-cycle ["a"] get-deps
                                      noop-visit
                                      (fn [_id _path _visited _visiting]
                                        (swap! cycle-count inc)
                                        nil)
                                      noop-missing)]
      (is (= #{"a" "b"} visited))
      (is (nil? result))
      (is (pos? @cycle-count)))))

(deftest dfs-missing-node-test
  (testing "missing dep halts when on-missing-fn returns non-nil"
    (let [missing-calls (atom [])
          [_visited result] (graph/dfs missing-dep-graph ["a"] get-deps
                                       noop-visit noop-cycle
                                       (fn [node-id _visited _visiting]
                                         (swap! missing-calls conj node-id)
                                         {:missing node-id}))]
      (is (= ["missing"] @missing-calls))
      (is (= {:missing "missing"} result))))

  (testing "missing dep continues when on-missing-fn returns nil"
    (let [missing-calls (atom [])
          [visited result] (graph/dfs missing-dep-graph ["a"] get-deps
                                      noop-visit noop-cycle
                                      (fn [node-id _visited _visiting]
                                        (swap! missing-calls conj node-id)
                                        nil))]
      (is (= ["missing"] @missing-calls))
      (is (nil? result))
      (is (= #{"a" "b"} visited)))))

(deftest dfs-start-ids-coercion-test
  (testing "single ID (not wrapped in collection) works"
    (let [[visited result] (graph/dfs single-node "a" get-deps
                                      noop-visit noop-cycle noop-missing)]
      (is (= #{"a"} visited))
      (is (nil? result))))

  (testing "vector of IDs works"
    (let [[visited _result] (graph/dfs forest ["a" "c"] get-deps
                                       noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited))))

  (testing "list of IDs works"
    (let [[visited _result] (graph/dfs forest '("a" "c") get-deps
                                       noop-visit noop-cycle noop-missing)]
      (is (= #{"a" "b" "c" "d"} visited)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run these tests:
  ;; bb test -- -n ai.miniforge.algorithms.graph-test

  :leave-this-here)
