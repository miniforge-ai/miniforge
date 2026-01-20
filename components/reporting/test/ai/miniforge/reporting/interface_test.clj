(ns ai.miniforge.reporting.interface-test
  "Tests for reporting component interface."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.reporting.interface :as reporting]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn create-mock-workflow-component
  "Create a mock workflow component for testing."
  []
  (let [workflows (atom [{:workflow/id (random-uuid)
                          :workflow/status :running
                          :workflow/phase :implement
                          :workflow/created-at (System/currentTimeMillis)
                          :workflow/metrics {:tokens 1000 :cost-usd 0.05}}
                         {:workflow/id (random-uuid)
                          :workflow/status :completed
                          :workflow/phase :done
                          :workflow/created-at (System/currentTimeMillis)
                          :workflow/metrics {:tokens 500 :cost-usd 0.025}}
                         {:workflow/id (random-uuid)
                          :workflow/status :failed
                          :workflow/phase :verify
                          :workflow/created-at (System/currentTimeMillis)
                          :workflow/metrics {:tokens 200 :cost-usd 0.01}}])]
    (reify
      ai.miniforge.workflow.protocol.Workflow
      (get-state [_this workflow-id]
        (if (= workflow-id :all)
          @workflows
          (first (filter #(= workflow-id (:workflow/id %)) @workflows)))))))

(defn create-mock-operator-component
  "Create a mock operator component for testing."
  []
  (let [signals (atom [{:signal/id (random-uuid)
                        :signal/type :workflow-failed
                        :signal/timestamp (System/currentTimeMillis)
                        :signal/data {:phase :implement}}])
        proposals (atom [{:improvement/id (random-uuid)
                          :improvement/type :rule-addition
                          :improvement/status :proposed
                          :improvement/confidence 0.85
                          :improvement/rationale "Add validation rule"}])]
    (reify
      ai.miniforge.operator.protocol.Operator
      (get-signals [_this _query] @signals)
      (get-proposals [_this query]
        (filter #(= (:status query) (:improvement/status %)) @proposals)))))

;------------------------------------------------------------------------------ Layer 1
;; System status tests

(deftest test-get-system-status
  (testing "get-system-status returns aggregated status"
    (let [[logger _entries] (log/collecting-logger)
          wf-component (create-mock-workflow-component)
          op-component (create-mock-operator-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component
                      :operator-component op-component
                      :logger logger})
          status (reporting/get-system-status reporting)]
      
      (is (map? status))
      (is (contains? status :workflows))
      (is (contains? status :resources))
      (is (contains? status :meta-loop))
      (is (contains? status :alerts))
      
      ;; Check workflow counts
      (is (= 1 (get-in status [:workflows :running])))
      (is (= 1 (get-in status [:workflows :completed])))
      (is (= 1 (get-in status [:workflows :failed])))
      
      ;; Check resource metrics
      (is (= 1700 (get-in status [:resources :tokens-used])))
      (is (= 0.085 (get-in status [:resources :cost-usd])))
      
      ;; Check meta-loop status
      (is (= 1 (get-in status [:meta-loop :pending-improvements]))))))

(deftest test-get-system-status-without-components
  (testing "get-system-status handles missing components gracefully"
    (let [reporting (reporting/create-reporting-service {})
          status (reporting/get-system-status reporting)]
      
      (is (map? status))
      (is (= 0 (get-in status [:workflows :active])))
      (is (= 0 (get-in status [:resources :tokens-used])))
      (is (= :not-configured (get-in status [:meta-loop :status]))))))

;------------------------------------------------------------------------------ Layer 2
;; Workflow list tests

(deftest test-get-workflow-list
  (testing "get-workflow-list returns all workflows"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          workflows (reporting/get-workflow-list reporting)]
      
      (is (= 3 (count workflows)))
      (is (every? #(contains? % :workflow/id) workflows))
      (is (every? #(contains? % :workflow/status) workflows))
      (is (every? #(contains? % :workflow/phase) workflows)))))

(deftest test-get-workflow-list-with-filtering
  (testing "get-workflow-list filters by status"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          running-workflows (reporting/get-workflow-list reporting {:status :running})]
      
      (is (= 1 (count running-workflows)))
      (is (= :running (:workflow/status (first running-workflows))))))
  
  (testing "get-workflow-list filters by phase"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          impl-workflows (reporting/get-workflow-list reporting {:phase :implement})]
      
      (is (= 1 (count impl-workflows)))
      (is (= :implement (:workflow/phase (first impl-workflows)))))))

(deftest test-get-workflow-list-with-limit
  (testing "get-workflow-list respects limit"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          workflows (reporting/get-workflow-list reporting {:limit 2})]
      
      (is (= 2 (count workflows))))))

;------------------------------------------------------------------------------ Layer 3
;; Workflow detail tests

(deftest test-get-workflow-detail
  (testing "get-workflow-detail returns workflow details"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          workflows (reporting/get-workflow-list reporting)
          workflow-id (:workflow/id (first workflows))
          detail (reporting/get-workflow-detail reporting workflow-id)]
      
      (is (map? detail))
      (is (contains? detail :header))
      (is (contains? detail :timeline))
      (is (contains? detail :current-task))
      (is (contains? detail :artifacts))
      (is (contains? detail :logs)))))

(deftest test-get-workflow-detail-not-found
  (testing "get-workflow-detail returns nil for non-existent workflow"
    (let [wf-component (create-mock-workflow-component)
          reporting (reporting/create-reporting-service
                     {:workflow-component wf-component})
          detail (reporting/get-workflow-detail reporting (random-uuid))]
      
      (is (nil? detail)))))

;------------------------------------------------------------------------------ Layer 4
;; Meta-loop status tests

(deftest test-get-meta-loop-status
  (testing "get-meta-loop-status returns operator data"
    (let [op-component (create-mock-operator-component)
          reporting (reporting/create-reporting-service
                     {:operator-component op-component})
          status (reporting/get-meta-loop-status reporting)]
      
      (is (map? status))
      (is (contains? status :signals))
      (is (contains? status :pending-improvements))
      (is (contains? status :recent-improvements))
      
      (is (= 1 (count (:signals status))))
      (is (= 1 (count (:pending-improvements status)))))))

(deftest test-get-meta-loop-status-without-operator
  (testing "get-meta-loop-status handles missing operator"
    (let [reporting (reporting/create-reporting-service {})
          status (reporting/get-meta-loop-status reporting)]
      
      (is (map? status))
      (is (empty? (:signals status)))
      (is (empty? (:pending-improvements status))))))

;------------------------------------------------------------------------------ Layer 5
;; Subscription tests

(deftest test-subscribe-and-unsubscribe
  (testing "subscribe creates subscription and unsubscribe removes it"
    (let [[logger entries] (log/collecting-logger)
          reporting (reporting/create-reporting-service {:logger logger})
          events (atom [])
          callback (fn [event] (swap! events conj event))
          sub-id (reporting/subscribe reporting #{:workflow-events} callback)]
      
      (is (uuid? sub-id))
      
      ;; Check subscription logged
      (is (some #(= :reporting/subscription-created (:event %)) @entries))
      
      ;; Unsubscribe
      (is (true? (reporting/unsubscribe reporting sub-id)))
      
      ;; Check unsubscribe logged
      (is (some #(= :reporting/subscription-removed (:event %)) @entries)))))

(deftest test-poll-events
  (testing "poll-events returns empty for new subscription"
    (let [reporting (reporting/create-reporting-service {})
          callback (fn [_event] nil)
          sub-id (reporting/subscribe reporting #{:workflow-events} callback)
          events (reporting/poll-events reporting sub-id)]
      
      (is (empty? events)))))

(deftest test-poll-events-nonexistent-subscription
  (testing "poll-events returns empty for nonexistent subscription"
    (let [reporting (reporting/create-reporting-service {})
          events (reporting/poll-events reporting (random-uuid))]
      
      (is (empty? events)))))
