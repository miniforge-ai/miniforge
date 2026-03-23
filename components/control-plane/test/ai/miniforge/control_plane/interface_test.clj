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

(ns ai.miniforge.control-plane.interface-test
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.control-plane.interface :as cp]))

;------------------------------------------------------------------------------ State Machine Tests

(deftest load-profile-test
  (testing "Profile loads from classpath"
    (let [profile (cp/load-profile)]
      (is (= :control-plane (:profile/id profile)))
      (is (vector? (:task-statuses profile)))
      (is (contains? (set (:task-statuses profile)) :running))
      (is (contains? (set (:task-statuses profile)) :blocked))
      (is (map? (:valid-transitions profile))))))

(deftest valid-transition-test
  (let [profile (cp/get-profile)]
    (testing "Valid transitions"
      (are [from to] (cp/valid-transition? profile from to)
        :unknown      :initializing
        :unknown      :running
        :running      :blocked
        :running      :idle
        :running      :completed
        :blocked      :running
        :paused       :running
        :unreachable  :running))

    (testing "Invalid transitions"
      (are [from to] (not (cp/valid-transition? profile from to))
        :completed :running
        :failed    :running
        :terminated :running
        :blocked   :completed))))

(deftest terminal-test
  (let [profile (cp/get-profile)]
    (testing "Terminal states"
      (is (cp/terminal? profile :completed))
      (is (cp/terminal? profile :failed))
      (is (cp/terminal? profile :terminated)))
    (testing "Non-terminal states"
      (is (not (cp/terminal? profile :running)))
      (is (not (cp/terminal? profile :blocked)))
      (is (not (cp/terminal? profile :idle))))))

(deftest event-mapping-test
  (let [profile (cp/get-profile)]
    (testing "Event maps to transition"
      (is (= :blocked (:to (cp/event->transition profile :agent/decision-needed))))
      (is (= :running (:to (cp/event->transition profile :agent/started)))))
    (testing "Unknown event returns nil"
      (is (nil? (cp/event->transition profile :bogus/event))))))

;------------------------------------------------------------------------------ Registry Tests

(deftest registry-crud-test
  (let [reg (cp/create-registry)]
    (testing "Register an agent"
      (let [agent (cp/register-agent! reg {:agent/vendor :claude-code
                                            :agent/external-id "session-123"
                                            :agent/name "Test Agent"
                                            :agent/capabilities #{:code-review}})]
        (is (uuid? (:agent/id agent)))
        (is (= :claude-code (:agent/vendor agent)))
        (is (= :unknown (:agent/status agent)))
        (is (= #{:code-review} (:agent/capabilities agent)))

        (testing "Get by ID"
          (is (= agent (cp/get-agent reg (:agent/id agent)))))

        (testing "Get by external ID"
          (is (= agent (cp/get-agent-by-external-id reg "session-123"))))

        (testing "List agents"
          (is (= 1 (count (cp/list-agents reg))))
          (is (= 1 (count (cp/list-agents reg {:vendor :claude-code}))))
          (is (= 0 (count (cp/list-agents reg {:vendor :openai})))))

        (testing "Count"
          (is (= 1 (cp/count-agents reg)))
          (is (= 1 (cp/count-agents reg :unknown)))
          (is (= 0 (cp/count-agents reg :running))))

        (testing "Update agent"
          (let [updated (cp/update-agent! reg (:agent/id agent)
                                          {:agent/task "Working on PR #42"})]
            (is (= "Working on PR #42" (:agent/task updated)))))

        (testing "Deregister"
          (let [removed (cp/deregister-agent! reg (:agent/id agent))]
            (is (some? removed))
            (is (nil? (cp/get-agent reg (:agent/id agent))))
            (is (= 0 (cp/count-agents reg)))))))))

(deftest heartbeat-test
  (let [reg (cp/create-registry)
        agent (cp/register-agent! reg {:agent/vendor :claude-code
                                        :agent/name "Test"})
        agent-id (:agent/id agent)]
    (testing "Record heartbeat with status change"
      (let [updated (cp/record-heartbeat! reg agent-id
                                          {:status :running :task "Reviewing"})]
        (is (= :running (:agent/status updated)))
        (is (= "Reviewing" (:agent/task updated)))))

    (testing "Invalid status change is ignored"
      ;; :running → :unknown is not valid
      (let [updated (cp/record-heartbeat! reg agent-id {:status :unknown})]
        (is (= :running (:agent/status updated)))))))

(deftest transition-agent-test
  (let [reg (cp/create-registry)
        agent (cp/register-agent! reg {:agent/vendor :test :agent/name "T"})
        agent-id (:agent/id agent)]
    (testing "Valid transition"
      (cp/transition-agent! reg agent-id :running)
      (is (= :running (:agent/status (cp/get-agent reg agent-id)))))

    (testing "Invalid transition throws"
      (cp/transition-agent! reg agent-id :completed)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid"
        (cp/transition-agent! reg agent-id :running))))))

(deftest agents-by-status-test
  (let [reg (cp/create-registry)]
    (cp/register-agent! reg {:agent/vendor :a :agent/name "A"})
    (let [b (cp/register-agent! reg {:agent/vendor :b :agent/name "B"})]
      (cp/transition-agent! reg (:agent/id b) :running)
      (let [grouped (cp/agents-by-status reg)]
        (is (= 1 (count (:unknown grouped))))
        (is (= 1 (count (:running grouped))))))))

;------------------------------------------------------------------------------ Decision Queue Tests

(deftest decision-crud-test
  (let [mgr (cp/create-decision-manager)
        agent-id (random-uuid)]
    (testing "Create and submit decision"
      (let [d (cp/create-decision agent-id "Merge PR #42?"
                                  {:type :approval
                                   :priority :high
                                   :options ["yes" "no"]})]
        (is (uuid? (:decision/id d)))
        (is (= :pending (:decision/status d)))
        (is (= :high (:decision/priority d)))
        (cp/submit-decision! mgr d)

        (testing "Get decision"
          (is (= d (cp/get-decision mgr (:decision/id d)))))

        (testing "Pending count"
          (is (= 1 (cp/count-pending mgr))))

        (testing "Resolve decision"
          (let [resolved (cp/resolve-decision! mgr (:decision/id d) "yes" "Ship it")]
            (is (= :resolved (:decision/status resolved)))
            (is (= "yes" (:decision/resolution resolved)))
            (is (= "Ship it" (:decision/comment resolved)))
            (is (some? (:decision/resolved-at resolved)))))

        (testing "Cannot resolve again"
          (is (nil? (cp/resolve-decision! mgr (:decision/id d) "no"))))

        (testing "Pending count after resolve"
          (is (= 0 (cp/count-pending mgr))))))))

(deftest decision-priority-ordering-test
  (let [mgr (cp/create-decision-manager)
        agent-id (random-uuid)
        d-low (cp/create-decision agent-id "Low priority" {:priority :low})
        d-high (cp/create-decision agent-id "High priority" {:priority :high})
        d-crit (cp/create-decision agent-id "Critical" {:priority :critical})
        d-med (cp/create-decision agent-id "Medium" {:priority :medium})]
    (cp/submit-decision! mgr d-low)
    (cp/submit-decision! mgr d-high)
    (cp/submit-decision! mgr d-crit)
    (cp/submit-decision! mgr d-med)

    (testing "Priority ordering: critical > high > medium > low"
      (let [pending (cp/pending-decisions mgr)]
        (is (= [:critical :high :medium :low]
               (mapv :decision/priority pending)))))))

(deftest decision-blocked-boost-test
  (let [mgr (cp/create-decision-manager)
        blocked-agent (random-uuid)
        normal-agent (random-uuid)
        d-normal (cp/create-decision normal-agent "Normal agent, high"
                                     {:priority :high})
        d-blocked (cp/create-decision blocked-agent "Blocked agent, high"
                                      {:priority :high})]
    ;; Submit normal first so it's older
    (cp/submit-decision! mgr d-normal)
    (Thread/sleep 10)
    (cp/submit-decision! mgr d-blocked)

    (testing "Blocked agent's decision appears first at same priority"
      (let [pending (cp/pending-decisions mgr #{blocked-agent})]
        (is (= blocked-agent (:decision/agent-id (first pending))))))))

(deftest decisions-for-agent-test
  (let [mgr (cp/create-decision-manager)
        a1 (random-uuid)
        a2 (random-uuid)]
    (cp/submit-decision! mgr (cp/create-decision a1 "D1"))
    (cp/submit-decision! mgr (cp/create-decision a1 "D2"))
    (cp/submit-decision! mgr (cp/create-decision a2 "D3"))

    (testing "Filter by agent"
      (is (= 2 (count (cp/decisions-for-agent mgr a1))))
      (is (= 1 (count (cp/decisions-for-agent mgr a2)))))))

(deftest cancel-decision-test
  (let [mgr (cp/create-decision-manager)
        d (cp/create-decision (random-uuid) "Cancel me")]
    (cp/submit-decision! mgr d)
    (is (= 1 (cp/count-pending mgr)))
    (cp/cancel-decision! mgr (:decision/id d))
    (is (= 0 (cp/count-pending mgr)))
    (is (= :cancelled (:decision/status (cp/get-decision mgr (:decision/id d)))))))

;------------------------------------------------------------------------------ Heartbeat Watchdog Tests

(deftest stale-agent-detection-test
  (let [reg (cp/create-registry)
        agent (cp/register-agent! reg {:agent/vendor :test
                                        :agent/name "Stale"
                                        :agent/heartbeat-interval-ms 1})]
    ;; Transition to running first
    (cp/transition-agent! reg (:agent/id agent) :running)
    ;; Wait for heartbeat to be stale (3 * 1ms)
    (Thread/sleep 50)
    (testing "Stale agent detected and marked unreachable"
      (let [transitioned (cp/check-stale-agents reg)]
        (is (= 1 (count transitioned)))
        (is (= :unreachable (:agent/status (cp/get-agent reg (:agent/id agent)))))))))
