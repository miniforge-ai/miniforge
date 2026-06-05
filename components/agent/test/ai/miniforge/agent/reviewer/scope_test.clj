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

(ns ai.miniforge.agent.reviewer.scope-test
  "Tests for `ai.miniforge.agent.reviewer.scope` — extracted from
   `ai.miniforge.agent.reviewer-test` as part of the PR #1039 decomposition.

   Covers resolution priority, the required-scope contract, per-file
   matching with boundary semantics, and the partition helper."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.reviewer.scope :as scope]))

;------------------------------------------------------------------------------ Factories
;; Factories per standards rule 400 (testing/factory-functions): any test data
;; map constructed more than once, or any map with more than 3 fields, comes
;; from a `defn-` factory. Repeated `{:severity :file :description}` shapes
;; below all flow through `review-issue`.

(defn- review-issue
  "Build a canonical-shape `ReviewIssue` map for tests. Defaults to the
   smallest valid issue; overrides via keyword args."
  [& {:keys [severity file description suggestion line]
      :or   {severity    :blocking
             description "test issue"}}]
  (cond-> {:severity severity :description description}
    file       (assoc :file file)
    suggestion (assoc :suggestion suggestion)
    line       (assoc :line line)))

(def ^:private sample-scope
  ["components/foo/src" "components/bar/src" "bases/cli/src/main.clj"])

;; The 2026-06-04 eliminate-requiring-resolve dogfood used directory-prefix
;; scope entries throughout (`components/foo/src`, `bases/cli/src`, ...);
;; mirror that shape so test coverage tracks production usage.

;------------------------------------------------------------------------------ effective-review-scope

(deftest effective-review-scope-priority-test
  (testing "task/scope wins over task/files-in-scope and intent/scope"
    (is (= ["explicit/path.clj"]
           (scope/effective-review-scope
             {:task/scope ["explicit/path.clj"]
              :task/files-in-scope ["dag/path.clj"]
              :task/intent {:scope ["intent/path.clj"]}}))))

  (testing "task/files-in-scope wins over intent/scope when task/scope absent"
    (is (= ["dag/per-task.clj"]
           (scope/effective-review-scope
             {:task/files-in-scope ["dag/per-task.clj"]
              :task/intent {:scope ["broad/spec-level.clj"]}}))))

  (testing "intent/scope is the final fallback"
    (is (= ["broad/spec-level/src"]
           (scope/effective-review-scope
             {:task/intent {:type :refactor
                            :scope ["broad/spec-level/src"]}}))))

  (testing "blank and non-string entries are filtered out"
    (is (= ["good/path.clj"]
           (scope/effective-review-scope
             {:task/scope ["good/path.clj" "" "   " nil 42]}))))

  (testing "duplicates within the winning source are collapsed"
    (is (= ["a.clj" "b.clj"]
           (scope/effective-review-scope
             {:task/scope ["a.clj" "a.clj" "b.clj" "a.clj"]})))))

(deftest effective-review-scope-missing-throws-test
  (testing "no scope signal anywhere → IllegalArgumentException (required-scope contract)"
    ;; PR #1039: the pre-#1039 nil-fallback ('review every file') silently
    ;; reintroduced the 2026-06-04 scope-creep pathology; missing scope is
    ;; now a programmer error so spec authors hear about it loudly.
    (is (thrown? IllegalArgumentException
                 (scope/effective-review-scope {})))
    (is (thrown? IllegalArgumentException
                 (scope/effective-review-scope {:task/intent "free-form prose"})))
    (is (thrown? IllegalArgumentException
                 (scope/effective-review-scope {:task/intent {:type :feature}}))))

  (testing "scope of all-blank/non-string entries is treated as missing"
    (is (thrown? IllegalArgumentException
                 (scope/effective-review-scope {:task/scope ["" "   " nil]})))))

;------------------------------------------------------------------------------ in-scope-issue?

(deftest in-scope-issue?-test
  (testing "directory prefix match"
    (is (scope/in-scope-issue? sample-scope
                               (review-issue :file "components/foo/src/inner.clj"))))

  (testing "exact file match"
    (is (scope/in-scope-issue? sample-scope
                               (review-issue :file "bases/cli/src/main.clj"))))

  (testing "boundary check — similar prefix is NOT a match"
    ;; This test actually exercises the boundary by using a scope entry
    ;; (`components/foo`) whose directory name is a strict prefix of
    ;; another directory (`components/foobar`). Without the trailing `/`
    ;; boundary in `in-scope-issue?`, this would be a false positive.
    ;; (PR #1039 comment fix: prior version of this test used scope entry
    ;; `components/foo/src` against `components/foobar/x.clj`, which
    ;; would pass even if the boundary logic were removed.)
    (is (not (scope/in-scope-issue? ["components/foo"]
                                    (review-issue :file "components/foobar/x.clj")))))

  (testing "trailing slash in scope entry doesn't affect matching"
    (is (scope/in-scope-issue? ["components/foo/src/"]
                               (review-issue :file "components/foo/src/inner.clj"))))

  (testing "out-of-scope file is rejected"
    (is (not (scope/in-scope-issue? sample-scope
                                    (review-issue :file "components/baz/src/x.clj")))))

  (testing "nil/blank :file is conservatively in-scope (file-level concern)"
    (is (scope/in-scope-issue? sample-scope (review-issue)))
    (is (scope/in-scope-issue? sample-scope (review-issue :file nil)))
    (is (scope/in-scope-issue? sample-scope (review-issue :file "  ")))))

;------------------------------------------------------------------------------ partition-issues-by-scope

(deftest partition-issues-by-scope-test
  (testing "partitioned in-scope and out-of-scope vectors"
    (let [issues [(review-issue :file "components/foo/src/a.clj"   :description "in")
                  (review-issue :file "components/baz/src/b.clj"   :description "out" :severity :warning)
                  (review-issue :file "bases/cli/src/main.clj"     :description "in")
                  (review-issue :file "components/quux/x.clj"      :description "out" :severity :nit)]
          {:keys [in-scope out-of-scope]}
          (scope/partition-issues-by-scope sample-scope issues)]
      (is (= 2 (count in-scope)))
      (is (= 2 (count out-of-scope)))
      (is (every? #(re-matches #"in"  (:description %)) in-scope))
      (is (every? #(re-matches #"out" (:description %)) out-of-scope))))

  (testing "empty input → both vecs empty"
    (is (= {:in-scope [] :out-of-scope []}
           (scope/partition-issues-by-scope sample-scope [])))))

;------------------------------------------------------------------------------ filter-applied-event factory

(deftest filter-applied-event-test
  (testing "factory builds the canonical telemetry payload shape"
    (let [payload (scope/filter-applied-event
                    {:scope          sample-scope
                     :filtered-count 3
                     :kept-count     2
                     :retry-path     :initial})]
      (is (= sample-scope (get-in payload [:data :scope])))
      (is (= 3            (get-in payload [:data :filtered-count])))
      (is (= 2            (get-in payload [:data :kept-count])))
      (is (= :initial     (get-in payload [:data :retry-path])))
      (is (string?        (get-in payload [:data :message]))
          "carries a localized human-readable message"))))
