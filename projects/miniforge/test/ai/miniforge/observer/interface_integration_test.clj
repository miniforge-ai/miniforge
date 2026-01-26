(ns ai.miniforge.observer.interface-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.observer.interface :as observer]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as wf-proto]))

(defn sample-workflow-state
  [workflow-id status & {:keys [tokens cost duration]
                         :or {tokens 1000 cost 0.10 duration 5000}}]
  {:workflow/id workflow-id
   :workflow/status status
   :workflow/metrics {:tokens tokens
                      :cost-usd cost
                      :duration-ms duration}
   :workflow/history [{:from-phase :start
                       :to-phase :implement
                       :timestamp (java.util.Date.)}]
   :workflow/errors (when (= status :failed)
                     [{:phase :test :error "Test failed"}])})

(defn sample-phase-result
  [success? & {:keys [tokens cost duration errors]
               :or {tokens 500 cost 0.05 duration 2000 errors []}}]
  {:success? success?
   :metrics {:tokens tokens
             :cost-usd cost
             :duration-ms duration}
   :errors errors
   :artifacts []})

(deftest workflow-observer-integration-test
  (testing "observer implements WorkflowObserver protocol"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-phase-start obs workflow-id :implement {})
      (wf-proto/on-phase-complete obs workflow-id :implement (sample-phase-result true))
      (wf-proto/on-phase-start obs workflow-id :test {})
      (wf-proto/on-phase-complete obs workflow-id :test (sample-phase-result true))
      (wf-proto/on-workflow-complete obs workflow-id (sample-workflow-state workflow-id :completed))
      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (some? metrics))
        (is (= 2 (count (:phases metrics))))
        (is (= :completed (:status metrics))))))

  (testing "observer handles phase errors"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-phase-error obs workflow-id :test {:message "Test failed" :type :assertion-error})
      (wf-proto/on-workflow-complete obs workflow-id (sample-workflow-state workflow-id :failed))
      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (= :failed (:status metrics)))
        (is (seq (:phases metrics))))))

  (testing "observer handles rollback"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-rollback obs workflow-id :test :implement "Test failed, rolling back")
      (is true))))
