(ns ai.miniforge.tui-views.chain-events-test
  "Tests for chain event translation and TUI model handlers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.subscription :as sub]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.msg :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; translate-event tests

(deftest translate-chain-started-test
  (testing "chain/started event translates to msg/chain-started"
    (let [event {:event/type :chain/started
                 :chain/id :plan-then-implement
                 :chain/step-count 3}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-started msg-type))
      (is (= :plan-then-implement (:chain-id payload)))
      (is (= 3 (:step-count payload))))))

(deftest translate-chain-step-started-test
  (testing "chain/step-started event translates correctly"
    (let [event {:event/type :chain/step-started
                 :chain/id :my-chain
                 :step/id :plan
                 :step/index 0
                 :step/workflow-id :planning-v1}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-step-started msg-type))
      (is (= :my-chain (:chain-id payload)))
      (is (= :plan (:step-id payload)))
      (is (= 0 (:step-index payload)))
      (is (= :planning-v1 (:workflow-id payload))))))

(deftest translate-chain-step-completed-test
  (testing "chain/step-completed event translates correctly"
    (let [event {:event/type :chain/step-completed
                 :chain/id :my-chain
                 :step/id :plan
                 :step/index 0}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-step-completed msg-type))
      (is (= :plan (:step-id payload)))
      (is (= 0 (:step-index payload))))))

(deftest translate-chain-step-failed-test
  (testing "chain/step-failed event translates correctly"
    (let [event {:event/type :chain/step-failed
                 :chain/id :my-chain
                 :step/id :implement
                 :step/index 1
                 :chain/error "LLM timeout"}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-step-failed msg-type))
      (is (= :implement (:step-id payload)))
      (is (= "LLM timeout" (:error payload))))))

(deftest translate-chain-completed-test
  (testing "chain/completed event translates correctly"
    (let [event {:event/type :chain/completed
                 :chain/id :my-chain
                 :chain/duration-ms 5000
                 :chain/step-count 3}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-completed msg-type))
      (is (= 5000 (:duration-ms payload)))
      (is (= 3 (:step-count payload))))))

(deftest translate-chain-failed-test
  (testing "chain/failed event translates correctly"
    (let [event {:event/type :chain/failed
                 :chain/id :my-chain
                 :chain/failed-step :implement
                 :chain/error "Step execution failed"}
          [msg-type payload] (sub/translate-event event)]
      (is (= :msg/chain-failed msg-type))
      (is (= :implement (:failed-step payload)))
      (is (= "Step execution failed" (:error payload))))))

;------------------------------------------------------------------------------ Layer 1
;; Event handler tests

(deftest handle-chain-started-test
  (testing "chain-started sets active-chain in model"
    (let [model (model/init-model)
          result (events/handle-chain-started model
                   {:chain-id :plan-then-implement :step-count 3})]
      (is (= :plan-then-implement (get-in result [:active-chain :chain-id])))
      (is (= 3 (get-in result [:active-chain :step-count])))
      (is (= :running (get-in result [:active-chain :status])))
      (is (nil? (get-in result [:active-chain :current-step])))
      (is (some? (:last-updated result))))))

(deftest handle-chain-step-started-test
  (testing "chain-step-started updates current step"
    (let [model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :current-step nil
                                         :status :running}))
          result (events/handle-chain-step-started model
                   {:chain-id :my-chain :step-id :plan
                    :step-index 0 :workflow-id :planning-v1})]
      (is (= :plan (get-in result [:active-chain :current-step :step-id])))
      (is (= 0 (get-in result [:active-chain :current-step :step-index])))
      (is (= :planning-v1 (get-in result [:active-chain :current-step :workflow-id]))))))

(deftest handle-chain-step-failed-test
  (testing "chain-step-failed marks chain as failed"
    (let [model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :current-step {:step-id :plan :step-index 0}
                                         :status :running}))
          result (events/handle-chain-step-failed model
                   {:chain-id :my-chain :step-index 0 :error "boom"})]
      (is (= :failed (get-in result [:active-chain :status])))
      (is (clojure.string/includes? (:flash-message result) "FAILED")))))

(deftest handle-chain-completed-test
  (testing "chain-completed clears active-chain"
    (let [model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :status :running}))
          result (events/handle-chain-completed model
                   {:chain-id :my-chain :duration-ms 5000 :step-count 2})]
      (is (nil? (:active-chain result)))
      (is (clojure.string/includes? (:flash-message result) "completed")))))

(deftest handle-chain-failed-test
  (testing "chain-failed marks chain as failed with error"
    (let [model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :status :running}))
          result (events/handle-chain-failed model
                   {:chain-id :my-chain :failed-step :implement
                    :error "LLM timeout"})]
      (is (= :failed (get-in result [:active-chain :status])))
      (is (clojure.string/includes? (:flash-message result) "FAILED"))
      (is (clojure.string/includes? (:flash-message result) "LLM timeout")))))

;------------------------------------------------------------------------------ Layer 2
;; Model initialization test

(deftest init-model-has-active-chain-test
  (testing "init-model includes :active-chain nil"
    (let [model (model/init-model)]
      (is (contains? model :active-chain))
      (is (nil? (:active-chain model))))))

;------------------------------------------------------------------------------ Layer 3
;; Chain instance linking and view indicator tests

(deftest workflow-added-links-chain-instance-test
  (testing "workflow-added during active chain step links instance-id"
    (let [wf-id (random-uuid)
          model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :current-step {:step-id :plan
                                                        :step-index 0
                                                        :workflow-id :planning-v1}
                                         :status :running}))
          result (events/handle-workflow-added model
                   {:workflow-id wf-id :name "plan-wf" :spec nil})]
      (is (= wf-id (get-in result [:active-chain :current-step :instance-id])))
      (is (= 1 (count (:workflows result)))))))

(deftest workflow-added-no-chain-leaves-model-unchanged-test
  (testing "workflow-added without active chain does not add chain info"
    (let [wf-id (random-uuid)
          model (model/init-model)
          result (events/handle-workflow-added model
                   {:workflow-id wf-id :name "solo-wf" :spec nil})]
      (is (nil? (:active-chain result)))
      (is (= 1 (count (:workflows result)))))))

(deftest workflow-added-does-not-overwrite-instance-id-test
  (testing "second workflow-added does not overwrite existing instance-id"
    (let [wf-id-1 (random-uuid)
          wf-id-2 (random-uuid)
          model (-> (model/init-model)
                    (assoc :active-chain {:chain-id :my-chain
                                         :step-count 2
                                         :current-step {:step-id :plan
                                                        :step-index 0
                                                        :workflow-id :planning-v1
                                                        :instance-id wf-id-1}
                                         :status :running}))
          result (events/handle-workflow-added model
                   {:workflow-id wf-id-2 :name "other-wf" :spec nil})]
      (is (= wf-id-1 (get-in result [:active-chain :current-step :instance-id]))
          "instance-id should not be overwritten"))))
