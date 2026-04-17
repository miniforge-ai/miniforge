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

(ns ai.miniforge.supervisory-state.accumulator-test
  "Unit tests for the supervisory-state event → entity accumulator."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.supervisory-state.accumulator :as acc]
   [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Helpers

(defn ev
  "Construct an event matching N3 §2.1's required envelope shape, merging
   in any extras. `:workflow/id` defaults to a random UUID."
  [event-type extras]
  (merge {:event/type            event-type
          :event/id              (random-uuid)
          :event/timestamp       (java.util.Date.)
          :event/version         "1.0.0"
          :event/sequence-number 0
          :workflow/id           (random-uuid)
          :message               "test"}
         extras))

;------------------------------------------------------------------------------ WorkflowRun

(deftest workflow-started-inserts-minimal-run
  (let [wf-id (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :workflow/started {:workflow/id wf-id}))
        run   (get-in table [:workflows wf-id])]
    (is (= wf-id (:workflow-run/id run)))
    (is (= :running (:workflow-run/status run)))
    (is (= :unknown (:workflow-run/current-phase run)))
    (is (some? (:workflow-run/started-at run)))
    (is (string? (:workflow-run/workflow-key run))
        "synthesized key is a string when event carries no spec")))

(deftest workflow-phase-started-updates-phase
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/phase-started
                                       {:workflow/id wf-id
                                        :workflow/phase :implement})))]
    (is (= :implement (get-in table [:workflows wf-id :workflow-run/current-phase])))))

(deftest workflow-phase-started-before-workflow-started-inserts-placeholder
  (let [wf-id (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :workflow/phase-started
                                   {:workflow/id wf-id
                                    :workflow/phase :plan}))]
    (is (= :plan (get-in table [:workflows wf-id :workflow-run/current-phase]))
        "phase-started creates a placeholder when no prior workflow/started")
    (is (= :running (get-in table [:workflows wf-id :workflow-run/status])))))

(deftest workflow-completed-sets-terminal-status
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started  {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/completed {:workflow/id wf-id})))]
    (is (= :completed (get-in table [:workflows wf-id :workflow-run/status])))))

(deftest workflow-failed-sets-failed-status
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/failed  {:workflow/id wf-id})))]
    (is (= :failed (get-in table [:workflows wf-id :workflow-run/status])))))

;------------------------------------------------------------------------------ AgentSession

(deftest cp-agent-registered-inserts-session
  (let [ag-id (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :control-plane/agent-registered
                                   {:cp/agent-id   ag-id
                                    :cp/vendor     :claude-code
                                    :cp/agent-name "impl-1"}))
        a     (get-in table [:agents ag-id])]
    (is (= ag-id (:agent/id a)))
    (is (= "claude-code" (:agent/vendor a)))
    (is (= "impl-1" (:agent/name a)))
    (is (= :idle (:agent/status a)))))

(deftest cp-agent-state-changed-updates-status
  (let [ag-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :control-plane/agent-registered
                                       {:cp/agent-id ag-id
                                        :cp/vendor   :claude-code
                                        :cp/agent-name "impl-1"}))
                  (acc/apply-event (ev :control-plane/agent-state-changed
                                       {:cp/agent-id   ag-id
                                        :cp/from-status :idle
                                        :cp/to-status   :executing})))]
    (is (= :executing (get-in table [:agents ag-id :agent/status])))))

(deftest cp-agent-heartbeat-touches-last-heartbeat
  (let [ag-id (random-uuid)
        t0    (java.util.Date. (long 1000000))
        t1    (java.util.Date. (long 2000000))
        table (-> schema/empty-table
                  (acc/apply-event (merge (ev :control-plane/agent-registered
                                              {:cp/agent-id ag-id
                                               :cp/vendor :claude-code
                                               :cp/agent-name "impl-1"})
                                          {:event/timestamp t0}))
                  (acc/apply-event (merge (ev :control-plane/agent-heartbeat
                                              {:cp/agent-id ag-id
                                               :cp/status :executing})
                                          {:event/timestamp t1})))]
    (is (= t1 (get-in table [:agents ag-id :agent/last-heartbeat])))))

(deftest unknown-agent-heartbeat-is-ignored
  (let [table (acc/apply-event schema/empty-table
                               (ev :control-plane/agent-heartbeat
                                   {:cp/agent-id (random-uuid)}))]
    (is (empty? (:agents table)))))

;------------------------------------------------------------------------------ PR

(deftest pr-created-then-merged
  (let [table (-> schema/empty-table
                  (acc/apply-event (ev :pr/created
                                       {:pr/repo "acme/widget"
                                        :pr/number 42
                                        :pr/title "Add thing"}))
                  (acc/apply-event (ev :pr/merged
                                       {:pr/repo "acme/widget"
                                        :pr/number 42})))
        pr    (get-in table [:prs ["acme/widget" 42]])]
    (is (= :merged (:pr/status pr)))
    (is (some? (:pr/merged-at pr)))))

;------------------------------------------------------------------------------ PolicyEvaluation

(deftest gate-passed-creates-evaluation
  (let [id    (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :gate/passed
                                   {:gate/id id
                                    :gate/target-type :pr
                                    :gate/target-id   ["acme/widget" 42]
                                    :gate/packs       ["core"]}))
        ev*   (get-in table [:policy-evals id])]
    (is (true? (:policy-eval/passed? ev*)))
    (is (= :pr (:policy-eval/target-type ev*)))
    (is (= ["core"] (:policy-eval/packs-applied ev*)))))

(deftest gate-failed-captures-violations
  (let [id    (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :gate/failed
                                   {:gate/id id
                                    :gate/violations [{:rule-id :no-force-push
                                                       :severity :critical
                                                       :category :security
                                                       :message "force push detected"
                                                       :remediable? false}]}))
        ev*   (get-in table [:policy-evals id])]
    (is (false? (:policy-eval/passed? ev*)))
    (is (= 1 (count (:policy-eval/violations ev*))))
    (is (= :no-force-push
           (-> ev* :policy-eval/violations first :violation/rule-id)))))

;------------------------------------------------------------------------------ Supervisory snapshot replay

(deftest supervisory-workflow-upserted-applies-baseline
  (let [wf-id  (random-uuid)
        entity {:workflow-run/id              wf-id
                :workflow-run/workflow-key    "auth-flow"
                :workflow-run/intent          "add login"
                :workflow-run/status          :completed
                :workflow-run/current-phase   :release
                :workflow-run/started-at      (java.util.Date.)
                :workflow-run/updated-at      (java.util.Date.)
                :workflow-run/trigger-source  :cli
                :workflow-run/correlation-id  wf-id}
        table  (acc/apply-event schema/empty-table
                                (ev :supervisory/workflow-upserted
                                    {:supervisory/entity entity}))]
    (is (= entity (get-in table [:workflows wf-id]))
        "snapshot events set the entity as an authoritative baseline")))

;------------------------------------------------------------------------------ Unknown event

(deftest unknown-event-type-is-noop
  (is (= schema/empty-table
         (acc/apply-event schema/empty-table
                          (ev :custom/frobnicated {})))))
