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

(ns ai.miniforge.pr-lifecycle.merge-test
  "Unit tests for merge policy and readiness evaluation.

   Tests merge policy defaults, merge methods, and readiness evaluation
   with mocked GitHub CLI responses."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.conflict-resolution :as conflict-resolution]
   [ai.miniforge.pr-lifecycle.merge :as merge]))

;------------------------------------------------------------------------------ Merge Methods

(deftest merge-methods-test
  (testing "All merge methods are defined"
    (is (= 3 (count merge/merge-methods)))
    (are [method] (contains? merge/merge-methods method)
      :merge :squash :rebase)))

;------------------------------------------------------------------------------ Default Merge Policy

(deftest default-merge-policy-test
  (testing "Default policy has expected values"
    (let [policy merge/default-merge-policy]
      (is (= :squash (:method policy))
          "Default method should be squash")
      (is (true? (:require-ci-green? policy)))
      (is (true? (:require-approvals? policy)))
      (is (= 1 (:required-approvals policy)))
      (is (true? (:require-no-unresolved-threads? policy)))
      (is (true? (:require-branch-up-to-date? policy)))
      (is (true? (:delete-branch-after-merge? policy)))
      (is (true? (:auto-rebase-on-stale? policy))))))

;------------------------------------------------------------------------------ Merge Readiness Evaluation

(deftest evaluate-merge-readiness-all-green-test
  (testing "All checks passing yields ready"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? true}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? true}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? true}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? false}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (true? (:ready? result)))
        (is (empty? (:blocking result)))))))

(deftest evaluate-merge-readiness-ci-not-green-test
  (testing "CI not green blocks merge"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? false}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? true}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? true}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? false}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (false? (:ready? result)))
        (is (some #{:ci-not-green} (:blocking result)))))))

(deftest evaluate-merge-readiness-not-approved-test
  (testing "Missing approval blocks merge"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? true}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? false}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? true}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? false}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (false? (:ready? result)))
        (is (some #{:not-approved} (:blocking result)))))))

(deftest evaluate-merge-readiness-branch-stale-test
  (testing "Stale branch blocks merge"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? true}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? true}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? false}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? false}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (false? (:ready? result)))
        (is (some #{:branch-not-up-to-date} (:blocking result)))))))

(deftest evaluate-merge-readiness-unresolved-threads-test
  (testing "Unresolved threads block merge"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? true}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? true}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? true}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? true}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (false? (:ready? result)))
        (is (some #{:unresolved-threads} (:blocking result)))))))

(deftest evaluate-merge-readiness-multiple-blockers-test
  (testing "Multiple blocking conditions are all reported"
    (with-redefs [merge/check-ci-status
                  (fn [_ _] (dag/ok {:ci-green? false}))
                  merge/check-review-status
                  (fn [_ _ _] (dag/ok {:approved? false}))
                  merge/check-branch-status
                  (fn [_ _] (dag/ok {:up-to-date? false}))
                  merge/check-unresolved-threads
                  (fn [_ _] (dag/ok {:has-unresolved? true}))]
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 merge/default-merge-policy)]
        (is (false? (:ready? result)))
        (is (= 4 (count (:blocking result))))))))

(deftest evaluate-merge-readiness-relaxed-policy-test
  (testing "Relaxed policy skips disabled checks"
    (let [relaxed-policy {:method :squash
                          :require-ci-green? false
                          :require-approvals? false
                          :require-no-unresolved-threads? false
                          :require-branch-up-to-date? false}
          ;; With all checks disabled, nothing should block
          result (merge/evaluate-merge-readiness
                   "/tmp" 123 relaxed-policy)]
      (is (true? (:ready? result)))
      (is (empty? (:blocking result))))))

;------------------------------------------------------------------------------ Attempt Merge with Mocks

(deftest attempt-merge-ready-and-succeeds-test
  (testing "Merge succeeds when ready"
    (with-redefs [merge/evaluate-merge-readiness
                  (fn [_ _ _] {:ready? true :checks {} :blocking []})
                  merge/merge-pr!
                  (fn [_ _ & _] (dag/ok {:merged? true :method :squash}))]
      (let [context {:dag-id (random-uuid) :run-id (random-uuid)
                     :task-id (random-uuid) :pr-id 123}
            result (merge/attempt-merge "/tmp" 123 merge/default-merge-policy context)]
        (is (dag/ok? result))
        (is (true? (:merged? (:data result))))))))

(deftest attempt-merge-not-ready-no-rebase-test
  (testing "Merge blocked without auto-rebase yields error"
    (with-redefs [merge/evaluate-merge-readiness
                  (fn [_ _ _] {:ready? false
                                :checks {}
                                :blocking [:ci-not-green]})]
      (let [policy (assoc merge/default-merge-policy :auto-rebase-on-stale? false)
            context {:dag-id (random-uuid) :run-id (random-uuid)
                     :task-id (random-uuid) :pr-id 123}
            result (merge/attempt-merge "/tmp" 123 policy context)]
        (is (dag/err? result))))))

;------------------------------------------------------------------------------ Stage 3d: conflict-resolution dispatch

(def ^:private conflicting-readiness
  "Readiness map shaped like evaluate-merge-readiness output where
   the branch check failed via CONFLICTING (DIRTY mergeStateStatus)."
  {:ready? false
   :checks {:branch (dag/ok {:up-to-date? false
                             :raw "{\"mergeable\":\"CONFLICTING\",\"mergeStateStatus\":\"DIRTY\"}"})}
   :blocking [:branch-not-up-to-date]})

(deftest attempt-merge-conflicting-engages-resolver-test
  (testing "When branch-check raw classifies as :conflicting AND
            policy enables auto-resolve AND context carries
            :resolve-fn, attempt-merge dispatches to
            conflict-resolution/resolve-pr-conflicts! and returns
            its outcome directly. The lifecycle's outer loop
            re-evaluates merge readiness on the next cycle once
            GitHub re-classifies the PR after the resolution
            commit lands."
    (let [resolver-called? (atom false)
          fake-success (dag/ok {:resolved? true :iterations 1
                                :pushed-sha "fake-sha"
                                :pr-branch "feat/x"})]
      (with-redefs [merge/evaluate-merge-readiness
                    (fn [_ _ _] conflicting-readiness)
                    conflict-resolution/resolve-pr-conflicts!
                    (fn [_]
                      (reset! resolver-called? true)
                      fake-success)
                    merge/run-gh-command
                    (fn [_ _]
                      (dag/ok {:output (str "{\"number\":123,"
                                            "\"headRefName\":\"feat/x\","
                                            "\"baseRefName\":\"main\","
                                            "\"headRefOid\":\"abc1234\","
                                            "\"baseRefOid\":\"def5678\"}")}))]
        (let [context {:dag-id (random-uuid) :run-id (random-uuid)
                       :task-id (random-uuid) :pr-id 123
                       :resolve-fn (fn [_] (dag/ok {}))}
              result (merge/attempt-merge "/tmp" 123
                                          merge/default-merge-policy
                                          context)]
          (is (true? @resolver-called?)
              "resolver was invoked for the :conflicting branch state")
          (is (= fake-success result)
              "attempt-merge returns the resolution outcome directly
               — no rebase fallback when conflict-resolution engaged"))))))

(deftest attempt-merge-conflicting-without-resolver-falls-through-test
  (testing "When the branch is :conflicting but context has no
            :resolve-fn (workflow side didn't inject one), the
            conflict-resolution dispatch declines (returns nil)
            and attempt-merge falls through to the existing
            rebase / not-ready logic. With auto-rebase off, ends
            up as :not-ready."
    (with-redefs [merge/evaluate-merge-readiness
                  (fn [_ _ _] conflicting-readiness)]
      (let [policy (assoc merge/default-merge-policy
                          :auto-rebase-on-stale? false)
            context {:dag-id (random-uuid) :run-id (random-uuid)
                     :task-id (random-uuid) :pr-id 123}
            ;; no :resolve-fn on context
            result (merge/attempt-merge "/tmp" 123 policy context)]
        (is (dag/err? result))
        (is (= :not-ready (:code (:error result)))
            "fell through to the not-ready path")))))

(deftest attempt-merge-conflicting-with-policy-disabled-falls-through-test
  (testing "Even with a :resolve-fn on context, if policy's
            :auto-resolve-conflicts? is false, the dispatch
            declines and we fall through to rebase/not-ready.
            Lets operators turn off auto-resolution per-PR
            (e.g. for a PR known to need human attention) without
            removing the wiring globally."
    (let [resolver-called? (atom false)]
      (with-redefs [merge/evaluate-merge-readiness
                    (fn [_ _ _] conflicting-readiness)
                    conflict-resolution/resolve-pr-conflicts!
                    (fn [_]
                      (reset! resolver-called? true)
                      (dag/ok {}))]
        (let [policy (assoc merge/default-merge-policy
                            :auto-resolve-conflicts? false
                            :auto-rebase-on-stale? false)
              context {:dag-id (random-uuid) :run-id (random-uuid)
                       :task-id (random-uuid) :pr-id 123
                       :resolve-fn (fn [_] (dag/ok {}))}
              result (merge/attempt-merge "/tmp" 123 policy context)]
          (is (false? @resolver-called?)
              "policy disabled — resolver never called")
          (is (dag/err? result))
          (is (= :not-ready (:code (:error result)))))))))