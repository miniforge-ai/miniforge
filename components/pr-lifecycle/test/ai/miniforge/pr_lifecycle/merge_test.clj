(ns ai.miniforge.pr-lifecycle.merge-test
  "Unit tests for merge policy and readiness evaluation.

   Tests merge policy defaults, merge methods, and readiness evaluation
   with mocked GitHub CLI responses."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.dag-executor.interface :as dag]
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
                          :require-branch-up-to-date? false}]
      ;; With all checks disabled, nothing should block
      (let [result (merge/evaluate-merge-readiness
                     "/tmp" 123 relaxed-policy)]
        (is (true? (:ready? result)))
        (is (empty? (:blocking result)))))))

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