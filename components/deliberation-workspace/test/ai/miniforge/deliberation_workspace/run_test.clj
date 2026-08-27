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
(ns ai.miniforge.deliberation-workspace.run-test
  (:require
   [ai.miniforge.deliberation-workspace.guards :as guards]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.run :as run]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stages
  (into validation/concurrency-stages guards/guard-stages))

(defn- ^{:stratum 0} goal [id]
  (object/new-object {:id id :type :goal :statement "ship the thing"
                      :role :interpreter :activation "act-0" :version 1}))

(defn- ^{:stratum 0} passes [_] nil)

(defn- ^{:stratum 0} events-of [ws kind]
  (filter #(= kind (:event %)) (:workspace/events ws)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} workspace [& {:as extra}]
  (merge {:workspace/version 1
          :workspace/objects {"goal-1" (goal "goal-1")}
          :workspace/roles [:proposer :skeptic :synthesizer]
          :workspace/eligibility {}
          :workspace/stages stages
          :workspace/log []
          :workspace/budget {:activations 3}}
         extra))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-run-closes-on-budget-and-forces-synthesis
  (let [closed (run/run (workspace) passes {})]
    (is (= :budget-boundary (get-in closed [:workspace/termination :termination/rule])))
    (is (get-in closed [:workspace/termination :termination/forced-synthesis]))
    (is (= 3 (get-in closed [:workspace/spent :activations])))))

(deftest ^{:stratum 2} a-run-closes-as-success-when-every-goal-is-terminal
  (let [ws (workspace :workspace/objects
                      {"goal-1" (assoc (goal "goal-1") :object/status :accepted)})]
    (is (= :success (get-in (run/run ws passes {})
                            [:workspace/termination :termination/rule])))))

(deftest ^{:stratum 2} a-passing-activation-still-costs-budget
  (testing "an activation that proposes nothing has still run"
    (let [after (run/step (workspace) passes)]
      (is (= 1 (get-in after [:workspace/spent :activations])))
      (is (= 1 (count (events-of after :transaction/passed)))))))

(deftest ^{:stratum 2} a-committed-transaction-advances-the-version
  (let [activate (fn [{:keys [role]}]
                   (tx/new-transaction
                    {:role role :activation "act-1" :basis 1
                     :operations [{:op :assert-claim
                                   :creates [{:id "claim-1" :type :claim
                                              :statement "a claim"}]}]}))
        after (run/step (workspace) activate)]
    (is (= 2 (:workspace/version after)))
    (is (some? (get-in after [:workspace/objects "claim-1"])))
    (is (= 1 (count (events-of after :transaction/committed))))))

(deftest ^{:stratum 2} a-rejected-transaction-is-logged-with-its-reason
  (let [activate (fn [{:keys [role]}]
                   (tx/new-transaction
                    {:role role :activation "act-1" :basis 1
                     :operations [{:op :not-an-operation}]}))
        after (run/step (workspace) activate)]
    (testing "the workspace does not advance"
      (is (= 1 (:workspace/version after))))
    (testing "but the activation is charged and the reason recorded"
      (is (= 1 (get-in after [:workspace/spent :activations])))
      (is (= :anomalies.deliberation/unknown-operation
             (:reason (first (events-of after :transaction/rejected))))))))

(deftest ^{:stratum 2} the-ablation-setting-reaches-the-projection
  (let [seen (atom nil)
        activate (fn [{:keys [projection]}] (reset! seen projection) nil)]
    (run/step (workspace :workspace/visibility :none) activate)
    (is (= :none (:projection/visibility @seen)))))

(deftest ^{:stratum 2} each-role-sees-a-delta-from-its-own-last-activation
  (let [seen (atom [])
        activate (fn [{:keys [role projection]}]
                   (swap! seen conj [role (:projection/version projection)])
                   nil)]
    (run/run (workspace) activate {})
    (testing "every activation renders at the current version"
      (is (= 3 (count @seen))))))

(deftest ^{:stratum 2} the-step-bound-is-a-defect-signal-not-a-normal-ending
  (let [endless (workspace :workspace/budget {} :workspace/quiescence-rounds 10000)
        closed (run/run endless passes {:max-steps 5})]
    (is (= :step-bound-exceeded
           (get-in closed [:workspace/termination :termination/rule])))))

(deftest ^{:stratum 2} missing-stages-fall-back-to-validation-not-to-nothing
  (testing "an empty chain would commit anything the activation proposed"
    (let [ws (dissoc (workspace) :workspace/stages)
          activate (fn [{:keys [role]}]
                     (tx/new-transaction {:role role :activation "act-1" :basis 1
                                          :operations [{:op :not-an-operation}]}))
          after (run/step ws activate)]
      (is (= 1 (:workspace/version after)) "nothing may commit unvalidated")
      (is (= :anomalies.deliberation/unknown-operation
             (:reason (first (events-of after :transaction/rejected))))))))

(deftest ^{:stratum 2} an-uneligible-workspace-does-not-charge-an-activation
  (testing "no role to run means no activation ran"
    (let [ws (workspace :workspace/roles [] :workspace/eligibility {})
          called (atom false)
          activate (fn [_] (reset! called true) nil)
          after (run/step ws activate)]
      (is (false? @called) "activate must not be invoked with a nil role")
      (is (nil? (get-in after [:workspace/spent :activations])))
      (is (= 1 (count (events-of after :activation/none-eligible)))))))

(deftest ^{:stratum 2} the-activation-event-records-what-triggered-it
  (let [conflict (object/new-object {:id "conflict-1" :type :conflict
                                     :statement "a contradiction"
                                     :role :engine :activation "derived"
                                     :version 1})
        ws (-> (workspace)
               (assoc-in [:workspace/objects "conflict-1"] conflict)
               (assoc :workspace/eligibility {:conflict [:skeptic]}))
        after (run/step ws passes)
        event (first (events-of after :activation/completed))]
    (is (= :conflict (:reason event)))
    (is (= "conflict-1" (:target event)) "the trigger must be auditable")))
