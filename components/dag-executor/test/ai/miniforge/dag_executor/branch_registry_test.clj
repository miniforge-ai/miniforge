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

(ns ai.miniforge.dag-executor.branch-registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.branch-registry :as br]))

;------------------------------------------------------------------------------ Layer 0
;; Registry primitives

(deftest create-registry-is-empty-test
  (testing "fresh registry is an empty map"
    (is (= {} (br/create-registry)))))

(deftest register-branch-stores-entry-test
  (testing "register-branch adds task-id → branch-info"
    (let [reg (-> (br/create-registry)
                  (br/register-branch :a {:branch "task-a"})
                  (br/register-branch :b {:branch "task-b" :commit-sha "abc"}))]
      (is (= {:branch "task-a"} (br/lookup-branch reg :a)))
      (is (= {:branch "task-b" :commit-sha "abc"} (br/lookup-branch reg :b))))))

(deftest register-branch-is-idempotent-on-re-register-test
  (testing "re-registering a task-id replaces the prior entry"
    (let [reg (-> (br/create-registry)
                  (br/register-branch :a {:branch "task-a-v1"})
                  (br/register-branch :a {:branch "task-a-v2"}))]
      (is (= {:branch "task-a-v2"} (br/lookup-branch reg :a))
          "later registration wins; retries don't accumulate"))))

(deftest lookup-branch-nil-for-unknown-test
  (testing "lookup-branch returns nil for tasks the registry hasn't seen"
    (is (nil? (br/lookup-branch (br/create-registry) :nope)))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-base-branch

(deftest resolve-zero-deps-returns-default-branch-test
  (testing "zero deps: root tasks acquire off the spec branch (backward-compatible)"
    (is (= "main" (br/resolve-base-branch (br/create-registry) [] "main")))
    (is (= "feat/x" (br/resolve-base-branch (br/create-registry) [] "feat/x")))))

(deftest resolve-single-dep-returns-deps-branch-test
  (testing "single dep registered: return that dep's branch"
    (let [reg (br/register-branch (br/create-registry) :a {:branch "task-a"})]
      (is (= "task-a" (br/resolve-base-branch reg [:a] "main"))))))

(deftest resolve-single-dep-falls-back-when-unregistered-test
  (testing "single dep not yet registered: fall back to default"
    ;; The scheduler is supposed to order tasks so deps complete first;
    ;; this fallback is defensive (registration race / persist-failed
    ;; upstream task). Falling back keeps the orchestrator from blocking.
    (is (= "main" (br/resolve-base-branch (br/create-registry) [:a] "main")))))

(deftest resolve-multiple-deps-returns-anomaly-test
  (testing "multiple deps: return :anomalies/dag-non-forest as data"
    (let [resolved (br/resolve-base-branch (br/create-registry) [:a :b] "main")]
      (is (br/resolve-error? resolved))
      (is (= :anomalies/dag-non-forest (:anomaly/category resolved)))
      (is (= [:a :b] (:task/dependencies resolved)))
      (is (string? (:anomaly/message resolved))
          "anomaly carries a human-readable message for downstream display"))))

(deftest resolve-error-predicate-test
  (testing "resolve-error? distinguishes anomaly maps from branch strings"
    (is (true?  (br/resolve-error? {:anomaly/category :anomalies/dag-non-forest})))
    (is (false? (br/resolve-error? "task-a")))
    (is (false? (br/resolve-error? nil)))
    (is (false? (br/resolve-error? {:branch "task-a"}))
        "unrelated maps are not anomalies")))

;------------------------------------------------------------------------------ Layer 2
;; validate-forest

(deftest validate-forest-empty-test
  (testing "empty plan validates as a (degenerate) forest"
    (is (nil? (br/validate-forest [])))))

(deftest validate-forest-roots-only-test
  (testing "tasks with no dependencies are always a forest"
    (is (nil? (br/validate-forest
              [{:task/id :a :task/dependencies []}
               {:task/id :b :task/dependencies []}])))))

(deftest validate-forest-linear-chain-test
  (testing "linear chain a → b → c is a forest"
    (is (nil? (br/validate-forest
              [{:task/id :a :task/dependencies []}
               {:task/id :b :task/dependencies [:a]}
               {:task/id :c :task/dependencies [:b]}])))))

(deftest validate-forest-tree-test
  (testing "tree where one node has multiple children is a forest"
    (is (nil? (br/validate-forest
              [{:task/id :a :task/dependencies []}
               {:task/id :b :task/dependencies [:a]}
               {:task/id :c :task/dependencies [:a]}]))
        "fan-out from a single parent is fine — multi-CHILDREN, not multi-PARENTS")))

(deftest validate-forest-diamond-fails-test
  (testing "diamond shape (one task with two parents) is not a forest"
    (let [anomaly (br/validate-forest
                  [{:task/id :a :task/dependencies []}
                   {:task/id :b :task/dependencies [:a]}
                   {:task/id :c :task/dependencies [:a]}
                   {:task/id :d :task/dependencies [:b :c]}])]
      (is (some? anomaly))
      (is (= :anomalies/dag-non-forest (:anomaly/category anomaly)))
      (is (= 1 (count (:multi-parent-tasks anomaly))))
      (let [violation (first (:multi-parent-tasks anomaly))]
        (is (= :d (:task/id violation)))
        (is (= 2  (:dep-count violation)))
        (is (= [:b :c] (:dependencies violation)))))))

(deftest validate-forest-reports-all-violations-test
  (testing "validate-forest reports every multi-parent task in one pass"
    (let [anomaly (br/validate-forest
                  [{:task/id :a :task/dependencies []}
                   {:task/id :b :task/dependencies []}
                   {:task/id :c :task/dependencies [:a :b]}
                   {:task/id :d :task/dependencies [:a :b :c]}])
          ids (set (map :task/id (:multi-parent-tasks anomaly)))]
      (is (= #{:c :d} ids)
          "both offending tasks surface so the user can fix the plan in one pass"))))

(deftest forest-predicate-test
  (testing "forest? is the boolean form of validate-forest"
    (is (true?  (br/forest? [{:task/id :a :task/dependencies []}])))
    (is (true?  (br/forest? [{:task/id :a :task/dependencies []}
                             {:task/id :b :task/dependencies [:a]}])))
    (is (false? (br/forest? [{:task/id :a :task/dependencies []}
                             {:task/id :b :task/dependencies []}
                             {:task/id :c :task/dependencies [:a :b]}])))))
