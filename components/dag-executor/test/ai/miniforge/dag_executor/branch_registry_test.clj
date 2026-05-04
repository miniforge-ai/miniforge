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

;------------------------------------------------------------------------------ Layer 3
;; Multi-parent base resolution primitives (v2)

(deftest multi-parent-predicate-test
  (testing "multi-parent? distinguishes task dep counts"
    (is (false? (br/multi-parent? [])))
    (is (false? (br/multi-parent? [:a])))
    (is (true?  (br/multi-parent? [:a :b])))
    (is (true?  (br/multi-parent? [:a :b :c])))))

(deftest resolve-multi-parent-base-preserves-order-test
  (testing "resolve-multi-parent-base returns parents in declaration order
            with :order indices matching the input vector position — that
            ordering is the user-controlled ordering source per spec §3.1
            and downstream collapse + first-parent-rule depend on it."
    (let [reg (-> (br/create-registry)
                  (br/register-branch :a {:branch "task-a" :commit-sha "aaa111"})
                  (br/register-branch :b {:branch "task-b" :commit-sha "bbb222"})
                  (br/register-branch :c {:branch "task-c" :commit-sha "ccc333"}))
          result (br/resolve-multi-parent-base reg [:a :b :c])
          parents (:merge/parents result)]
      (is (= 3 (count parents)))
      (is (= [:a :b :c] (mapv :task/id parents)))
      (is (= [0 1 2] (mapv :order parents)))
      (is (= "task-a" (:branch (first parents))))
      (is (= "aaa111" (:commit-sha (first parents)))))))

(deftest resolve-multi-parent-base-skips-unregistered-test
  (testing "resolve-multi-parent-base silently skips unregistered deps —
            fail-soft (matches the single-parent path's behavior). The
            orchestrator detects the size mismatch via :order indices
            and decides whether to fall back or anomaly per its policy."
    (let [reg (-> (br/create-registry)
                  (br/register-branch :a {:branch "task-a" :commit-sha "aaa"})
                  ;; :b not registered
                  (br/register-branch :c {:branch "task-c" :commit-sha "ccc"}))
          result (br/resolve-multi-parent-base reg [:a :b :c])
          parents (:merge/parents result)]
      (is (= 2 (count parents)))
      (is (= [:a :c] (mapv :task/id parents))
          "registered-only deps survive")
      (is (= [0 2] (mapv :order parents))
          ":order preserves the input position so the caller can detect the gap"))))

(deftest resolve-multi-parent-base-empty-when-nothing-registered-test
  (testing "resolve-multi-parent-base returns nil when no deps resolve,
            matching the spec §3.2 'caller should use single-parent fast
            path before calling' guidance."
    (is (nil? (br/resolve-multi-parent-base (br/create-registry) [:a :b])))))

(deftest collapse-duplicate-tips-no-duplicates-test
  (testing "collapse-duplicate-tips is identity when all SHAs are distinct"
    (let [parents [{:task/id :a :branch "ta" :commit-sha "aaa" :order 0}
                   {:task/id :b :branch "tb" :commit-sha "bbb" :order 1}]
          result (br/collapse-duplicate-tips parents)]
      (is (= parents (:parents result)))
      (is (empty? (:collapsed result))))))

(deftest collapse-duplicate-tips-drops-later-duplicates-test
  (testing "collapse-duplicate-tips keeps the first parent at a SHA and
            drops later parents at the same SHA, recording which absorbed
            which so the orchestrator can log the collapse."
    (let [parents [{:task/id :a :branch "ta" :commit-sha "shared-sha" :order 0}
                   {:task/id :b :branch "tb" :commit-sha "unique-sha" :order 1}
                   {:task/id :c :branch "tc" :commit-sha "shared-sha" :order 2}]
          result (br/collapse-duplicate-tips parents)]
      (is (= [:a :b] (mapv :task/id (:parents result)))
          "first parent at each unique SHA survives")
      (is (= [{:dropped :c :duplicate-of :a}] (:collapsed result))
          "the dropped parent and its absorber are recorded for logging"))))

(deftest collapse-duplicate-tips-treats-nil-commit-sha-as-unknown-test
  (testing "collapse-duplicate-tips never collapses against a nil
            :commit-sha — unknown is unknown; the orchestrator must
            populate SHAs before relying on duplicate collapse."
    (let [parents [{:task/id :a :branch "ta" :commit-sha nil :order 0}
                   {:task/id :b :branch "tb" :commit-sha nil :order 1}]
          result (br/collapse-duplicate-tips parents)]
      (is (= 2 (count (:parents result)))
          "two unknown SHAs do not count as duplicates of each other")
      (is (empty? (:collapsed result))))))

(deftest compute-input-key-is-deterministic-test
  (testing "compute-input-key returns the same hash for the same inputs"
    (let [parents [{:commit-sha "aaa"} {:commit-sha "bbb"}]
          k1 (br/compute-input-key :task-x :git-merge parents)
          k2 (br/compute-input-key :task-x :git-merge parents)]
      (is (= k1 k2))
      (is (string? k1))
      (is (= 16 (count k1))
          "16-hex-char truncation: enough collision resistance for the
           per-task namespace, short enough to read in logs"))))

(deftest compute-input-key-changes-with-inputs-test
  (testing "compute-input-key produces distinct keys for distinct inputs —
            this is what makes the namespaced ref name stable across
            replays AND distinguishable across different plans / parents."
    (let [parents-1 [{:commit-sha "aaa"} {:commit-sha "bbb"}]
          parents-2 [{:commit-sha "aaa"} {:commit-sha "ccc"}]
          parents-reordered [{:commit-sha "bbb"} {:commit-sha "aaa"}]]
      (is (not= (br/compute-input-key :t :git-merge parents-1)
                (br/compute-input-key :t :git-merge parents-2))
          "different parent SHAs ⇒ different key")
      (is (not= (br/compute-input-key :t1 :git-merge parents-1)
                (br/compute-input-key :t2 :git-merge parents-1))
          "different task-ids ⇒ different key")
      (is (not= (br/compute-input-key :t :git-merge      parents-1)
                (br/compute-input-key :t :sequential-merge parents-1))
          "different strategies ⇒ different key")
      (is (not= (br/compute-input-key :t :git-merge parents-1)
                (br/compute-input-key :t :git-merge parents-reordered))
          "parent ORDER matters — first-parent rule means a different
           order would produce a different merge commit, so the key
           must reflect the order"))))

(deftest compute-input-key-throws-on-nil-commit-sha-test
  (testing "compute-input-key fails fast when any parent's :commit-sha is
            nil. This is a programming error — the orchestrator must
            populate commit SHAs at registration time. Silently producing
            a key from an ambiguous input would let two genuinely different
            merges collide on the same ref."
    (let [bad-parents [{:task/id :a :commit-sha "aaa"}
                       {:task/id :b :commit-sha nil}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"parent commit-sha is nil"
                            (br/compute-input-key :task-x :git-merge bad-parents)))
      (try
        (br/compute-input-key :task-x :git-merge bad-parents)
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= [:b] (:parents-with-nil-sha data))
                "ex-data names the offending parents so the orchestrator
                 can log/escalate without re-deriving them")))))))
