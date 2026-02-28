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

(ns ai.miniforge.algorithms.interface-test
  "Tests for the public algorithms interface: dfs-find, dfs-validate-graph,
   dfs-collect, and dfs."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.algorithms.interface :as algorithms]))

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

(def direct-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["a"]}})

(def self-cycle
  {"a" {:id "a" :deps ["a"]}})

(def indirect-cycle
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["c"]}
   "c" {:id "c" :deps ["a"]}})

(def missing-dep-graph
  {"a" {:id "a" :deps ["b"]}
   "b" {:id "b" :deps ["missing"]}})

(def cycle-and-missing-graph
  {"a" {:id "a" :deps ["b" "c"]}
   "b" {:id "b" :deps ["a"]}
   "c" {:id "c" :deps ["gone"]}})

;------------------------------------------------------------------------------ dfs-find tests

(deftest dfs-find-basic-test
  (testing "find node d in diamond graph returns path"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path] (= id "d")))]
      (is (some? result))
      (is (= "d" (:found-id result)))
      (is (vector? (:path result)))
      (is (= "d" (last (:path result))))
      (is (= "a" (first (:path result))))))

  (testing "find start node itself"
    (let [result (algorithms/dfs-find single-node "a" get-deps
                                      (fn [id _node _path] (= id "a")))]
      (is (= "a" (:found-id result)))
      (is (= ["a"] (:path result)))))

  (testing "node not found returns nil"
    (let [result (algorithms/dfs-find diamond-dag "a" get-deps
                                      (fn [id _node _path] (= id "zzz")))]
      (is (nil? result)))))

(deftest dfs-find-edge-cases-test
  (testing "empty graph returns nil"
    (is (nil? (algorithms/dfs-find empty-graph "a" get-deps
                                   (fn [_id _node _path] true)))))

  (testing "single node found"
    (let [result (algorithms/dfs-find single-node "a" get-deps
                                      (fn [id _node _path] (= id "a")))]
      (is (= {:found-id "a" :path ["a"]} result))))

  (testing "graph with cycle, target reachable from cycle path"
    (let [result (algorithms/dfs-find indirect-cycle "a" get-deps
                                      (fn [id _node _path] (= id "c")))]
      (is (= "c" (:found-id result)))))

  (testing "graph with cycle, target unreachable returns nil (no infinite loop)"
    (let [result (algorithms/dfs-find direct-cycle "a" get-deps
                                      (fn [id _node _path] (= id "zzz")))]
      (is (nil? result)))))

;------------------------------------------------------------------------------ dfs-validate-graph tests

(deftest dfs-validate-graph-valid-test
  (testing "linear chain is valid"
    (let [result (algorithms/dfs-validate-graph
                   linear-chain (keys linear-chain) get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle"})))]
      (is (:valid? result))
      (is (= linear-chain (:graph result)))))

  (testing "diamond DAG is valid"
    (let [result (algorithms/dfs-validate-graph
                   diamond-dag (keys diamond-dag) get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle"})))]
      (is (:valid? result))))

  (testing "single node is valid"
    (let [result (algorithms/dfs-validate-graph
                   single-node ["a"] get-deps
                   (fn [_id _node _ctx] nil))]
      (is (:valid? result))))

  (testing "empty graph with empty start-nodes is valid"
    (let [result (algorithms/dfs-validate-graph
                   empty-graph [] get-deps
                   (fn [_id _node _ctx] nil))]
      (is (:valid? result)))))

(deftest dfs-validate-graph-cycle-test
  (testing "direct cycle detected via validate-fn"
    (let [result (algorithms/dfs-validate-graph
                   direct-cycle ["a"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "cycle" :path (:path context)})))]
      (is (false? (:valid? result)))
      (is (= "cycle" (:error result)))
      (is (vector? (:path result)))))

  (testing "self-cycle detected"
    (let [result (algorithms/dfs-validate-graph
                   self-cycle ["a"] get-deps
                   (fn [_id _node context]
                     (when (:cycle? context)
                       {:valid? false :error "self-cycle"})))]
      (is (false? (:valid? result))))))

(deftest dfs-validate-graph-missing-test
  (testing "missing dep detected via validate-fn"
    (let [result (algorithms/dfs-validate-graph
                   missing-dep-graph ["a"] get-deps
                   (fn [node-id _node context]
                     (when (:missing? context)
                       {:valid? false :error "missing" :node-id node-id})))]
      (is (false? (:valid? result)))
      (is (= "missing" (:error result)))
      (is (= "missing" (:node-id result))))))

;------------------------------------------------------------------------------ dfs-collect tests

(deftest dfs-collect-visit-test
  (testing "collect all node IDs on :visit in diamond graph"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (vector? result))
      (is (= #{"a" "b" "c" "d"} (set result)))
      (is (= 4 (count result))
          "d should not be duplicated")))

  (testing "empty graph returns empty vector"
    (let [result (algorithms/dfs-collect empty-graph [] get-deps
                                         (fn [id _path _v _vis] id)
                                         :visit)]
      (is (= [] result)))))

(deftest dfs-collect-cycle-test
  (testing "graph with cycle, collect-on :cycle returns cycle info"
    (let [result (algorithms/dfs-collect direct-cycle ["a"] get-deps
                                         (fn [id path _v _vis]
                                           {:cycle-node id :path path})
                                         :cycle)]
      (is (vector? result))
      (is (pos? (count result)))
      (is (= "a" (:cycle-node (first result))))))

  (testing "no cycles returns empty vector"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id path _v _vis]
                                           {:cycle-node id :path path})
                                         :cycle)]
      (is (= [] result)))))

(deftest dfs-collect-missing-test
  (testing "graph with missing deps, collect-on :missing"
    (let [result (algorithms/dfs-collect missing-dep-graph ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :missing)]
      (is (= ["missing"] result))))

  (testing "no missing returns empty vector"
    (let [result (algorithms/dfs-collect diamond-dag ["a"] get-deps
                                         (fn [id _path _v _vis] id)
                                         :missing)]
      (is (= [] result)))))

(deftest dfs-collect-all-test
  (testing "collect-on :all collects visits, cycles, and missing"
    (let [result (algorithms/dfs-collect cycle-and-missing-graph ["a"] get-deps
                                         (fn [id _path _v _vis]
                                           {:id id})
                                         :all)]
      (is (vector? result))
      (let [ids (set (map :id result))]
        (is (contains? ids "a") "a should appear (visited and/or cycled)")
        (is (contains? ids "b") "b should appear (visited)")
        (is (contains? ids "c") "c should appear (visited)")
        (is (contains? ids "gone") "gone should appear (missing)")))))

;------------------------------------------------------------------------------ Interface delegation tests

(deftest interface-vars-exist-test
  (testing "all public API vars are functions"
    (is (fn? algorithms/dfs))
    (is (fn? algorithms/dfs-find))
    (is (fn? algorithms/dfs-validate-graph))
    (is (fn? algorithms/dfs-collect))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run these tests:
  ;; bb test -- -n ai.miniforge.algorithms.interface-test

  :leave-this-here)
