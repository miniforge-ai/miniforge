(ns ai.miniforge.event-stream.control-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.control :as control]))

(deftest create-control-action-test
  (testing "creates action with required fields"
    (let [action (es/create-control-action
                  :pause
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})]
      (is (uuid? (:action/id action)))
      (is (= :pause (:action/type action)))
      (is (= :pending (:action/status action)))
      (is (inst? (:action/created-at action)))
      (is (= :workflow (get-in action [:action/target :target-type])))))

  (testing "creates action with justification and parameters"
    (let [action (es/create-control-action
                  :rollback
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin}
                  {:justification "Deployment failed"
                   :parameters {:to-commit "abc123"}})]
      (is (= "Deployment failed" (:action/justification action)))
      (is (= {:to-commit "abc123"} (:action/parameters action))))))

(deftest authorize-action-test
  (testing "operator can pause workflow"
    (let [action (es/create-control-action
                  :pause
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "ops" :role :operator})
          result (es/authorize-action
                  control/default-roles action {:role :operator})]
      (is (true? (:authorized? result)))))

  (testing "operator cannot rollback workflow"
    (let [action (es/create-control-action
                  :rollback
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "ops" :role :operator})
          result (es/authorize-action
                  control/default-roles action {:role :operator})]
      (is (false? (:authorized? result)))
      (is (string? (:reason result)))))

  (testing "admin can rollback workflow"
    (let [action (es/create-control-action
                  :rollback
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/authorize-action
                  control/default-roles action {:role :admin})]
      (is (true? (:authorized? result)))))

  (testing "operator cannot emergency-stop fleet"
    (let [action (es/create-control-action
                  :emergency-stop
                  {:target-type :fleet :target-id (random-uuid)}
                  {:principal "ops" :role :operator})
          result (es/authorize-action
                  control/default-roles action {:role :operator})]
      (is (false? (:authorized? result)))))

  (testing "admin can emergency-stop fleet"
    (let [action (es/create-control-action
                  :emergency-stop
                  {:target-type :fleet :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/authorize-action
                  control/default-roles action {:role :admin})]
      (is (true? (:authorized? result)))))

  (testing "unknown role is unauthorized"
    (let [action (es/create-control-action
                  :pause
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "unknown" :role :intern})
          result (es/authorize-action
                  control/default-roles action {:role :intern})]
      (is (false? (:authorized? result))))))

(deftest execute-control-action-test
  (testing "successful execution emits events and returns success"
    (let [stream (es/create-event-stream {:sinks []})
          events (atom [])
          _ (es/subscribe! stream :test (fn [e] (swap! events conj e)))
          action (es/create-control-action
                  :pause
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/execute-control-action!
                  stream action
                  (fn [_act] {:paused true}))]
      (is (= :success (:status result)))
      (is (= {:paused true} (:result result)))
      ;; Should have emitted requested + executed events
      (let [action-events (filter #(#{:control-action/requested :control-action/executed}
                                     (:event/type %)) @events)]
        (is (= 2 (count action-events)))
        (is (= :control-action/requested (:event/type (first action-events))))
        (is (= :control-action/executed (:event/type (second action-events)))))))

  (testing "failed execution emits events and returns failure"
    (let [stream (es/create-event-stream {:sinks []})
          action (es/create-control-action
                  :rollback
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/execute-control-action!
                  stream action
                  (fn [_act] (throw (ex-info "Rollback failed" {}))))]
      (is (= :failure (:status result)))
      (is (string? (get-in result [:result :error]))))))

(deftest default-roles-test
  (testing "operator has expected workflow permissions"
    (is (= #{:pause :resume :retry :cancel}
           (get-in control/default-roles [:operator :workflows]))))

  (testing "admin has superset of operator permissions"
    (let [op-wf (get-in control/default-roles [:operator :workflows])
          admin-wf (get-in control/default-roles [:admin :workflows])]
      (is (every? admin-wf op-wf))))

  (testing "admin has approval permissions"
    (is (seq (get-in control/default-roles [:admin :approvals])))))
