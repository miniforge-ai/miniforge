(ns ai.miniforge.workflow.interface-test
  "Tests for the workflow component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.interface :as wf]
   [ai.miniforge.workflow.protocol :as proto]))

;; ============================================================================
;; Workflow creation tests
;; ============================================================================

(deftest create-workflow-test
  (testing "create-workflow returns a workflow"
    (let [workflow (wf/create-workflow)]
      (is (some? workflow))
      (is (satisfies? proto/Workflow workflow)))))

;; ============================================================================
;; Workflow lifecycle tests
;; ============================================================================

(deftest workflow-start-test
  (testing "start creates a new workflow"
    (let [workflow (wf/create-workflow)
          spec {:title "Test workflow" :description "A test"}
          workflow-id (wf/start workflow spec {})]
      (is (uuid? workflow-id))

      (testing "get-state returns workflow state"
        (let [state (wf/get-state workflow workflow-id)]
          (is (some? state))
          (is (= workflow-id (:workflow/id state)))
          (is (= :spec (:workflow/phase state)))
          (is (= :pending (:workflow/status state))))))))

(deftest workflow-advance-test
  (testing "advance moves workflow to next phase"
    (let [workflow (wf/create-workflow)
          workflow-id (wf/start workflow {:title "Test"} {})]

      ;; Initial phase is :spec
      (is (= :spec (:workflow/phase (wf/get-state workflow workflow-id))))

      ;; Advance with success
      (let [new-state (wf/advance workflow workflow-id
                                   {:success? true
                                    :artifacts [{:type :plan :content "plan"}]
                                    :errors []
                                    :metrics {:tokens 100}})]
        (is (= :plan (:workflow/phase new-state)))
        (is (= 1 (count (:workflow/artifacts new-state))))))))

(deftest workflow-rollback-test
  (testing "rollback returns to earlier phase"
    (let [workflow (wf/create-workflow)
          workflow-id (wf/start workflow {:title "Test"} {})]

      ;; Advance to :plan
      (wf/advance workflow workflow-id {:success? true :artifacts [] :errors []})

      ;; Rollback to :spec
      (let [state (wf/rollback workflow workflow-id :spec :test-reason)]
        (is (= :spec (:workflow/phase state)))
        (is (= 1 (:workflow/rollback-count state)))))))

(deftest workflow-complete-test
  (testing "complete marks workflow as done"
    (let [workflow (wf/create-workflow)
          workflow-id (wf/start workflow {:title "Test"} {})]

      (let [state (wf/complete workflow workflow-id)]
        (is (= :done (:workflow/phase state)))
        (is (= :completed (:workflow/status state)))))))

(deftest workflow-fail-test
  (testing "fail marks workflow as failed"
    (let [workflow (wf/create-workflow)
          workflow-id (wf/start workflow {:title "Test"} {})]

      (let [state (wf/fail workflow workflow-id {:type :test :message "Failed"})]
        (is (= :failed (:workflow/status state)))
        (is (= 1 (count (:workflow/errors state))))))))

;; ============================================================================
;; Phase definitions tests
;; ============================================================================

(deftest phases-test
  (testing "phases are defined"
    (is (vector? wf/phases))
    (is (contains? (set wf/phases) :plan))
    (is (contains? (set wf/phases) :implement))
    (is (contains? (set wf/phases) :verify))))

(deftest phase-transitions-test
  (testing "phase transitions are valid"
    (is (map? wf/phase-transitions))
    (is (contains? (get wf/phase-transitions :plan) :implement))
    (is (contains? (get wf/phase-transitions :implement) :verify))))

;; ============================================================================
;; Observer tests
;; ============================================================================

(deftest observer-test
  (testing "observers receive callbacks"
    (let [workflow (wf/create-workflow)
          events (atom [])
          observer (reify proto/WorkflowObserver
                     (on-phase-start [_ wf-id phase _ctx]
                       (swap! events conj {:event :start :workflow-id wf-id :phase phase}))
                     (on-phase-complete [_ wf-id phase _result]
                       (swap! events conj {:event :complete :workflow-id wf-id :phase phase}))
                     (on-phase-error [_ wf-id phase _error]
                       (swap! events conj {:event :error :workflow-id wf-id :phase phase}))
                     (on-workflow-complete [_ wf-id _state]
                       (swap! events conj {:event :workflow-complete :workflow-id wf-id}))
                     (on-rollback [_ wf-id from to _reason]
                       (swap! events conj {:event :rollback :from from :to to})))]

      (wf/add-observer workflow observer)
      (let [workflow-id (wf/start workflow {:title "Test"} {})]
        (is (some #(= :start (:event %)) @events))
        (is (= workflow-id (:workflow-id (first @events))))))))
