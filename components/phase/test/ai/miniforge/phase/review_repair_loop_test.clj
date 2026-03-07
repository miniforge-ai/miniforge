(ns ai.miniforge.phase.review-repair-loop-test
  "Tests for the review → implement repair loop.

   Validates that when a reviewer returns :changes-requested, the execution
   engine redirects back to :implement (not re-running :review in place)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.phase.review]
   [ai.miniforge.phase.implement]))

;; ============================================================================
;; leave-review status tests
;; ============================================================================

(defn simulate-leave-review
  "Simulate the leave-review logic for a given decision and iteration state.
   Returns the phase map after leave-review processing."
  [decision iterations max-iterations]
  (let [result {:output {:review/decision decision
                         :review/issues [{:severity :blocking
                                          :description "Missing require"}]}
                :metrics {:tokens 100 :duration-ms 5000}}
        ctx {:phase {:started-at (- (System/currentTimeMillis) 1000)
                     :iterations iterations
                     :budget {:iterations max-iterations}
                     :result result}
             :phase-config {:phase :review}
             :execution/phase-results {}
             :execution/input {:description "test task"}
             :execution/metrics {}}
        ;; Call leave-review via the interceptor
        interceptor (registry/get-phase-interceptor {:phase :review})
        leave-fn (:leave interceptor)
        result-ctx (leave-fn ctx)]
    (:phase result-ctx)))

;; ============================================================================
;; Core behavior tests
;; ============================================================================

(deftest changes-requested-sets-failed-status
  (testing "changes-requested sets :failed status (not :retrying)"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= :failed (:status phase))
          "Status should be :failed so execution engine uses redirect-to")
      (is (not (registry/retrying? phase))
          "Should NOT be retrying (would cause review to re-run in place)"))))

(deftest changes-requested-within-budget-redirects-to-implement
  (testing "changes-requested within budget sets redirect-to :implement"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= :implement (:redirect-to phase))
          "Should redirect to implement for repair")
      (is (registry/failed? phase)
          "Must be failed for redirect-to to be honored by execution engine"))))

(deftest changes-requested-over-budget-no-redirect
  (testing "changes-requested at max iterations fails without redirect"
    (let [phase (simulate-leave-review :changes-requested 4 4)]
      (is (= :failed (:status phase))
          "Should be failed")
      (is (nil? (:redirect-to phase))
          "Should NOT redirect — iteration budget exhausted"))))

(deftest changes-requested-stores-review-feedback
  (testing "changes-requested stores review issues as feedback"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (some? (:review-feedback phase))
          "Review feedback should be stored for implement to consume")
      (is (= [{:severity :blocking :description "Missing require"}]
             (:review-feedback phase))
          "Should contain the review issues"))))

(deftest approved-sets-completed-status
  (testing "approved review sets :completed status"
    (let [phase (simulate-leave-review :approved 1 4)]
      (is (= :completed (:status phase))
          "Approved review should complete normally")
      (is (nil? (:redirect-to phase))
          "No redirect needed for approved review"))))

(deftest iteration-counter-incremented-on-redirect
  (testing "iteration counter increments when redirecting"
    (let [phase (simulate-leave-review :changes-requested 1 4)]
      (is (= 2 (:iterations phase))
          "Iterations should increment from 1 to 2"))))

;; ============================================================================
;; Execution engine integration: failed + redirect-to triggers redirect
;; ============================================================================

(deftest failed-with-redirect-not-confused-with-retrying
  (testing "execution engine distinguishes failed+redirect from retrying"
    (let [phase-result {:status :failed :redirect-to :implement}]
      ;; The key invariant: failed? is true, retrying? is false
      (is (registry/failed? phase-result)
          "Should be detected as failed")
      (is (not (registry/retrying? phase-result))
          "Should NOT be detected as retrying")
      ;; This means determine-next-index will hit the redirect branch,
      ;; not the retrying branch (which would stay at current index)
      )))

(deftest retrying-status-stays-at-current-phase
  (testing "retrying status would stay at current phase (the old broken behavior)"
    (let [phase-result {:status :retrying}]
      (is (registry/retrying? phase-result)
          "retrying? returns true for :retrying status")
      ;; This is why the old code was broken: :retrying caused review
      ;; to re-run itself instead of redirecting to implement
      )))

;; ============================================================================
;; Build-implement-task includes review feedback from context
;; ============================================================================

(deftest implement-task-includes-review-feedback-from-context
  (testing "build-implement-task passes review-feedback through to task"
    ;; The implement phase reads review feedback from execution/phase-results
    ;; (survives :phase clearing between phases) and includes it as :task/review-feedback
    (let [build-implement-task #'ai.miniforge.phase.implement/build-implement-task
          ctx {:execution/input {:description "Build widget"}
               :execution/phase-results {:plan {:result {:output nil}}
                                         :review {:result {:output {:review/decision :changes-requested
                                                                     :review/feedback [{:severity :blocking
                                                                                         :description "Missing require"}]}}}}}
          task (build-implement-task ctx)]
      (is (= [{:severity :blocking :description "Missing require"}]
             (:task/review-feedback task))
          "Task should include review feedback from context"))))

(deftest implement-task-omits-review-feedback-when-absent
  (testing "build-implement-task works normally without review feedback"
    (let [build-implement-task #'ai.miniforge.phase.implement/build-implement-task
          ctx {:execution/input {:description "Build widget"}
               :execution/phase-results {:plan {:result {:output nil}}}}
          task (build-implement-task ctx)]
      (is (nil? (:task/review-feedback task))
          "Task should not have review feedback when none in context"))))
