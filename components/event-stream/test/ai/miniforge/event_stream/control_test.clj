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

(ns ai.miniforge.event-stream.control-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.control :as control]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Control state primitive tests

(deftest create-control-state-test
  (testing "returns atom with expected shape"
    (let [cs (control/create-control-state)]
      (is (instance? clojure.lang.Atom cs))
      (is (= {:paused false :stopped false :adjustments {}} @cs)))))

(deftest pause!-test
  (testing "sets :paused to true"
    (let [cs (control/create-control-state)]
      (control/pause! cs)
      (is (true? (:paused @cs))))))

(deftest resume!-test
  (testing "sets :paused to false"
    (let [cs (control/create-control-state)]
      (control/pause! cs)
      (control/resume! cs)
      (is (false? (:paused @cs))))))

(deftest cancel!-test
  (testing "sets :stopped to true"
    (let [cs (control/create-control-state)]
      (control/cancel! cs)
      (is (true? (:stopped @cs))))))

(deftest paused?-test
  (testing "returns correct value"
    (let [cs (control/create-control-state)]
      (is (false? (control/paused? cs)))
      (control/pause! cs)
      (is (true? (control/paused? cs)))
      (control/resume! cs)
      (is (false? (control/paused? cs))))))

(deftest cancelled?-test
  (testing "returns correct value"
    (let [cs (control/create-control-state)]
      (is (false? (control/cancelled? cs)))
      (control/cancel! cs)
      (is (true? (control/cancelled? cs))))))

(deftest control-state-interface-delegation-test
  (testing "interface delegates to control functions"
    (let [cs (es/create-control-state)]
      (is (false? (es/paused? cs)))
      (es/pause! cs)
      (is (true? (es/paused? cs)))
      (es/resume! cs)
      (is (false? (es/paused? cs)))
      (is (false? (es/cancelled? cs)))
      (es/cancel! cs)
      (is (true? (es/cancelled? cs))))))

;------------------------------------------------------------------------------ Layer 1
;; RBAC and control action tests

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
      (is (false? (:authorized? result)))))

  (testing "unauthorized result includes anomaly"
    (let [action (es/create-control-action
                  :rollback
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "ops" :role :operator})
          result (es/authorize-action
                  control/default-roles action {:role :operator})]
      (is (false? (:authorized? result)))
      (is (response/anomaly-map? (:anomaly result))))))

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
      (is (= {:paused true} (:output result)))
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
      (is (false? (:success result)))
      (is (string? (get-in result [:error :message]))))))

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
