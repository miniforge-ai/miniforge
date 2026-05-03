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

(deftest workflow-phase-completed-accumulates-artifact-ids
  (let [wf-id (random-uuid)
        artifact-a (random-uuid)
        artifact-b (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/phase-completed
                                       {:workflow/id wf-id
                                        :phase/artifacts [artifact-a {:artifact/id artifact-b}]}))
                  (acc/apply-event (ev :workflow/phase-completed
                                       {:workflow/id wf-id
                                        :phase/artifacts [{:artifact/id artifact-a}]})))]
    (is (= [artifact-a artifact-b]
           (get-in table [:workflows wf-id :workflow-run/artifact-ids])))))

(deftest workflow-completed-captures-owned-prs
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/completed
                                       {:workflow/id wf-id
                                        :workflow/pr-info {:pr-number 42
                                                           :pr-url "https://github.com/acme/widget/pull/42"
                                                           :branch "feature/widget"}})))
        prs   (get-in table [:workflows wf-id :workflow-run/prs])]
    (is (= 1 (count prs)))
    (is (= {:pr/repo "acme/widget"
            :pr/number 42
            :pr/url "https://github.com/acme/widget/pull/42"
            :pr/branch "feature/widget"}
           (select-keys (first prs) [:pr/repo :pr/number :pr/url :pr/branch])))))

(deftest workflow-failed-sets-failed-status
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/failed  {:workflow/id wf-id})))]
    (is (= :failed (get-in table [:workflows wf-id :workflow-run/status])))))

(deftest workflow-cancelled-sets-cancelled-status
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :workflow/started {:workflow/id wf-id}))
                  (acc/apply-event (ev :workflow/cancelled {:workflow/id wf-id})))]
    (is (= :cancelled (get-in table [:workflows wf-id :workflow-run/status])))))

;------------------------------------------------------------------------------ DependencyHealth

(defn- dependency-health-attrs
  [attrs]
  (merge {:dependency/id :anthropic
          :dependency/source :external-provider
          :dependency/kind :provider
          :dependency/vendor :anthropic
          :dependency/window-size 5}
         attrs))

(deftest dependency-health-updated-inserts-projection
  (let [table (acc/apply-event schema/empty-table
                               (ev :dependency/health-updated
                                   (dependency-health-attrs
                                    {:dependency/status :degraded
                                     :dependency/failure-count 1
                                     :dependency/incident-counts {:degraded 1}})))
        dependency (get-in table [:dependencies :anthropic])]
    (is (= :degraded (:dependency/status dependency)))
    (is (= :provider (:dependency/kind dependency)))))

(deftest dependency-recovered-resets-entity-to-healthy
  (let [table (-> schema/empty-table
                  (acc/apply-event (ev :dependency/health-updated
                                       (dependency-health-attrs
                                        {:dependency/status :unavailable
                                         :dependency/failure-count 3
                                         :dependency/incident-counts {:unavailable 3}})))
                  (acc/apply-event (ev :dependency/recovered
                                       (dependency-health-attrs {}))))]
    (is (= :healthy (get-in table [:dependencies :anthropic :dependency/status])))
    (is (= 0 (get-in table [:dependencies :anthropic :dependency/failure-count])))))

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
  (let [wf-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :pr/created
                                       {:pr/repo "acme/widget"
                                        :workflow/id wf-id
                                        :pr/number 42
                                        :pr/title "Add thing"}))
                  (acc/apply-event (ev :pr/merged
                                       {:pr/repo "acme/widget"
                                        :pr/number 42})))
        pr    (get-in table [:prs ["acme/widget" 42]])]
    (is (= :merged (:pr/status pr)))
    (is (some? (:pr/merged-at pr)))
    (is (= wf-id (:pr/workflow-run-id pr)))))

;------------------------------------------------------------------------------ :pr/scored

(def ^:private sample-readiness
  {:readiness/score     0.82
   :readiness/threshold 0.80
   :readiness/ready?    true
   :readiness/factors   [{:factor :ci-passed :weight 0.3 :score 1.0 :contribution 0.3}]})

(def ^:private sample-risk
  {:risk/score   0.35
   :risk/level   :medium
   :risk/factors [{:factor :change-size :weight 0.4 :value 420 :score 0.4}]})

(def ^:private sample-policy
  {:policy/overall       :pass
   :policy/packs-applied ["core"]
   :policy/summary       {:critical 0 :major 0 :minor 0 :info 0 :total 0}
   :policy/violations    []})

(deftest pr-scored-merges-into-existing-pr
  (let [table (-> schema/empty-table
                  (acc/apply-event (ev :pr/created
                                       {:pr/repo "acme/widget"
                                        :pr/number 42
                                        :pr/title "Add thing"}))
                  (acc/apply-event (ev :pr/scored
                                       {:pr/repo "acme/widget"
                                        :pr/number 42
                                        :pr/readiness sample-readiness
                                        :pr/risk sample-risk
                                        :pr/policy sample-policy
                                        :pr/recommendation :approve})))
        pr    (get-in table [:prs ["acme/widget" 42]])]
    (is (= "Add thing" (:pr/title pr)) "existing fields preserved")
    (is (= sample-readiness (:pr/readiness pr)))
    (is (= sample-risk (:pr/risk pr)))
    (is (= sample-policy (:pr/policy pr)))
    (is (= :approve (:pr/recommendation pr)))))

(deftest pr-scored-before-pr-created-stubs-minimal-entry
  (let [table (acc/apply-event schema/empty-table
                               (ev :pr/scored
                                   {:pr/repo "acme/widget"
                                    :pr/number 42
                                    :pr/readiness sample-readiness
                                    :pr/recommendation :review}))
        pr    (get-in table [:prs ["acme/widget" 42]])]
    (is (some? pr) "stub created when :pr/scored arrives before :pr/created")
    (is (= sample-readiness (:pr/readiness pr)))
    (is (= :review (:pr/recommendation pr)))
    (is (= "acme/widget" (:pr/repo pr)))
    (is (= 42 (:pr/number pr)))))

(deftest pr-scored-partial-does-not-clobber-previous-scores
  (let [table (-> schema/empty-table
                  (acc/apply-event (ev :pr/created
                                       {:pr/repo "acme/widget"
                                        :pr/number 42}))
                  (acc/apply-event (ev :pr/scored
                                       {:pr/repo "acme/widget"
                                        :pr/number 42
                                        :pr/readiness sample-readiness
                                        :pr/risk sample-risk
                                        :pr/policy sample-policy
                                        :pr/recommendation :merge}))
                  ;; A second :pr/scored event with ONLY recommendation and
                  ;; risk; readiness + policy should be retained.
                  (acc/apply-event (ev :pr/scored
                                       {:pr/repo "acme/widget"
                                        :pr/number 42
                                        :pr/risk {:risk/score 0.7
                                                  :risk/level :high
                                                  :risk/factors []}
                                        :pr/recommendation :escalate})))
        pr    (get-in table [:prs ["acme/widget" 42]])]
    (is (= :high (:risk/level (:pr/risk pr))) "risk updated")
    (is (= :escalate (:pr/recommendation pr)) "recommendation updated")
    (is (= sample-readiness (:pr/readiness pr)) "readiness preserved across partial re-score")
    (is (= sample-policy (:pr/policy pr)) "policy preserved across partial re-score")))

;------------------------------------------------------------------------------ TaskNode (N5-δ3 §2.3)

(defn- task-event
  "Build a `:task/state-changed` event for tests."
  [task-id wf-id to-state & [context]]
  (ev :task/state-changed
      (cond-> {:task/id       task-id
               :workflow/id   wf-id
               :task/to-state to-state}
        context (assoc :task/context context))))

(deftest task-state-changed-creates-entry-with-kanban-column
  (let [tid (random-uuid)
        wf  (random-uuid)
        table (acc/apply-event schema/empty-table
                               (task-event tid wf :pending))
        task  (get-in table [:tasks tid])]
    (is (some? task))
    (is (= tid (:task/id task)))
    (is (= wf (:task/workflow-run-id task)))
    (is (= :pending (:task/status task)))
    (is (= :blocked (:task/kanban-column task))
        ":pending must map to :blocked column (conservative default)")))

(deftest task-kanban-column-derives-from-each-known-status
  (doseq [[status column] {:pending         :blocked
                           :ready           :ready
                           :running         :active
                           :ci-running      :active
                           :review-pending  :in-review
                           :ready-to-merge  :merging
                           :merging         :merging
                           :merged          :done
                           :completed       :done
                           :failed          :done
                           :skipped         :done
                           :cancelled       :done}]
    (let [tid   (random-uuid)
          table (acc/apply-event schema/empty-table
                                 (task-event tid (random-uuid) status))
          task  (get-in table [:tasks tid])]
      (is (= column (:task/kanban-column task))
          (str "status " status " should map to column " column
               ", got " (:task/kanban-column task))))))

(deftest task-kanban-mapping-is-loaded-from-edn-not-compiled-in
  (testing "status→column mapping is data, not a compiled-in literal"
    (let [m (acc/load-task-kanban-mapping)]
      (is (map? m))
      (is (= :blocked (get m :pending)))
      (is (= :active  (get m :running)))
      (is (= :done    (get m :completed))))))

(deftest task-unknown-status-falls-back-to-blocked
  (let [tid   (random-uuid)
        table (acc/apply-event schema/empty-table
                               (task-event tid (random-uuid) :some-new-profile-status))
        task  (get-in table [:tasks tid])]
    (is (= :some-new-profile-status (:task/status task))
        "producer's status keyword is preserved verbatim")
    (is (= :blocked (:task/kanban-column task))
        "unknown status must fall back to :blocked so it surfaces visibly")))

(deftest task-context-fields-populate-opportunistically
  (let [tid   (random-uuid)
        deps  [(random-uuid) (random-uuid)]
        table (acc/apply-event schema/empty-table
                               (task-event tid (random-uuid) :running
                                           {:description "Implement widget"
                                            :type :implement
                                            :component "widget"
                                            :dependencies deps}))
        task  (get-in table [:tasks tid])]
    (is (= "Implement widget" (:task/description task)))
    (is (= :implement (:task/type task)))
    (is (= "widget" (:task/component task)))
    (is (= deps (:task/dependencies task)))))

(deftest task-started-at-captured-on-entry-to-active-column
  (let [tid   (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (task-event tid (random-uuid) :pending))
                  (acc/apply-event (task-event tid (random-uuid) :running)))
        task  (get-in table [:tasks tid])]
    (is (some? (:task/started-at task))
        ":task/started-at should be set when task first enters :active column")
    (is (nil? (:task/completed-at task)))))

(deftest task-completed-at-and-elapsed-ms-on-terminal
  (let [tid   (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (task-event tid (random-uuid) :running))
                  (acc/apply-event (task-event tid (random-uuid) :completed)))
        task  (get-in table [:tasks tid])]
    (is (some? (:task/completed-at task)))
    (is (some? (:task/elapsed-ms task)))
    (is (>= (:task/elapsed-ms task) 0))))

(deftest task-state-changed-preserves-context-across-transitions
  (let [tid   (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (task-event tid (random-uuid) :pending
                                               {:description "do the thing"
                                                :type :implement}))
                  ;; Second event with no context — description must be preserved.
                  (acc/apply-event (task-event tid (random-uuid) :running)))
        task  (get-in table [:tasks tid])]
    (is (= "do the thing" (:task/description task)))
    (is (= :implement (:task/type task)))
    (is (= :running (:task/status task)))))

(deftest supervisory-task-node-upserted-applies-baseline
  (let [tid   (random-uuid)
        entity {:task/id              tid
                :task/workflow-run-id (random-uuid)
                :task/description     "pre-scored task"
                :task/status          :running
                :task/kanban-column   :active}
        table (acc/apply-event schema/empty-table
                               (ev :supervisory/task-node-upserted
                                   {:supervisory/entity entity}))]
    (is (= entity (get-in table [:tasks tid]))
        "snapshot replay trusts the carried entity verbatim")))

;------------------------------------------------------------------------------ DecisionCard (N5-δ3 §2.4)

(defn- decision-created-event
  [decision-id agent-id summary & [extras]]
  (ev :control-plane/decision-created
      (merge {:cp/agent-id    agent-id
              :cp/decision-id decision-id
              :cp/summary     summary}
             extras)))

(defn- decision-resolved-event
  [decision-id resolution & [extras]]
  (ev :control-plane/decision-resolved
      (merge {:cp/decision-id decision-id
              :cp/resolution  resolution}
             extras)))

(deftest cp-decision-created-makes-pending-card
  (let [did   (random-uuid)
        aid   (random-uuid)
        table (acc/apply-event schema/empty-table
                               (decision-created-event did aid "Approve the merge"
                                                       {:cp/priority :high}))
        card  (get-in table [:decisions did])]
    (is (some? card))
    (is (= did (:decision/id card)))
    (is (= aid (:decision/agent-id card)))
    (is (= "Approve the merge" (:decision/summary card)))
    (is (= :high (:decision/priority card)))
    (is (= :pending (:decision/status card)))
    (is (some? (:decision/created-at card)))
    (is (nil? (:decision/resolved-at card)) "not resolved until a resolve event arrives")))

(deftest cp-decision-created-copies-rich-fields
  (let [did   (random-uuid)
        aid   (random-uuid)
        due   (java.util.Date.)
        table (acc/apply-event schema/empty-table
                               (decision-created-event did aid "Choose target"
                                                       {:cp/type :choice
                                                        :cp/context "Production migration"
                                                        :cp/options ["blue" "green"]
                                                        :cp/deadline due}))
        card  (get-in table [:decisions did])]
    (is (= :choice (:decision/type card)))
    (is (= "Production migration" (:decision/context card)))
    (is (= ["blue" "green"] (:decision/options card)))
    (is (= due (:decision/deadline card)))))

(deftest cp-decision-created-attaches-workflow-run-id-when-present
  (let [did   (random-uuid)
        wf    (random-uuid)
        table (acc/apply-event schema/empty-table
                               (ev :control-plane/decision-created
                                   {:cp/agent-id    (random-uuid)
                                    :cp/decision-id did
                                    :cp/summary     "x"
                                    :workflow/id    wf}))
        card  (get-in table [:decisions did])]
    (is (= wf (:decision/workflow-run-id card)))))

(deftest cp-decision-resolved-updates-existing-card
  (let [did   (random-uuid)
        aid   (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (decision-created-event did aid "Choose target"))
                  (acc/apply-event (decision-resolved-event did "approve"
                                                            {:cp/comment "lgtm"})))
        card  (get-in table [:decisions did])]
    (is (= :resolved (:decision/status card)))
    (is (= "approve" (:decision/resolution card)))
    (is (= "lgtm" (:decision/comment card)))
    (is (some? (:decision/resolved-at card)))
    ;; Creation fields must survive the resolve update.
    (is (= aid (:decision/agent-id card)))
    (is (= "Choose target" (:decision/summary card)))))

(deftest cp-agent-heartbeat-updates-task-and-metrics
  (let [aid (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (ev :control-plane/agent-registered
                                       {:cp/agent-id aid
                                        :cp/vendor :claude-code}))
                  (acc/apply-event (ev :control-plane/agent-heartbeat
                                       {:cp/agent-id aid
                                        :cp/status :running
                                        :cp/task "Reviewing PR #42"
                                        :cp/metrics {:tokens 12}})))
        agent (get-in table [:agents aid])]
    (is (= "Reviewing PR #42" (:agent/task agent)))
    (is (= {:tokens 12} (:agent/metrics agent)))))

(deftest cp-decision-resolved-before-created-stubs-minimal-card
  (let [did   (random-uuid)
        table (acc/apply-event schema/empty-table
                               (decision-resolved-event did "approve"))
        card  (get-in table [:decisions did])]
    (is (some? card) "resolve-before-create must not drop the resolution")
    (is (= did (:decision/id card)))
    (is (= :resolved (:decision/status card)))
    (is (= "approve" (:decision/resolution card)))
    (is (nil? (:decision/agent-id card))
        "stub agent-id is nil until the create event arrives")))

(deftest cp-decision-resolved-into-nonexistent-then-created-merges
  ;; Resolve arrives first (replay gap), then create — both fields survive.
  (let [did   (random-uuid)
        aid   (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (decision-resolved-event did "approve"))
                  (acc/apply-event (decision-created-event did aid "x")))
        card  (get-in table [:decisions did])]
    ;; The later create event overwrote the stub — that's acceptable
    ;; behaviour (create always reflects initial state; consumers get a
    ;; pending entry, then see resolved on the next resolve event).
    (is (= :pending (:decision/status card)))
    (is (= aid (:decision/agent-id card)))))

(deftest supervisory-decision-upserted-applies-baseline
  (let [did    (random-uuid)
        entity {:decision/id        did
                :decision/agent-id  (random-uuid)
                :decision/status    :pending
                :decision/summary   "test"
                :decision/created-at (java.util.Date.)}
        table  (acc/apply-event schema/empty-table
                                (ev :supervisory/decision-upserted
                                    {:supervisory/entity entity}))]
    (is (= entity (get-in table [:decisions did])))))

;------------------------------------------------------------------------------ InterventionRequest

(defn- intervention-requested-event
  [intervention-id intervention-type target-type target-id & [extra]]
  (ev :supervisory/intervention-requested
      (merge {:intervention/id intervention-id
              :intervention/type intervention-type
              :intervention/target-type target-type
              :intervention/target-id target-id
              :intervention/requested-by "operator@example.com"
              :intervention/request-source :tui
              :intervention/state :proposed
              :intervention/requested-at (java.util.Date.)
              :intervention/updated-at (java.util.Date.)}
             extra)))

(defn- intervention-state-changed-event
  [intervention-id next-state & [extra]]
  (ev :supervisory/intervention-state-changed
      (merge {:intervention/id intervention-id
              :intervention/state next-state}
             extra)))

(deftest intervention-requested-creates-record
  (let [intervention-id (random-uuid)
        workflow-id (random-uuid)
        table (acc/apply-event schema/empty-table
                               (intervention-requested-event intervention-id
                                                             :pause
                                                             :workflow
                                                             workflow-id))
        request (get-in table [:interventions intervention-id])]
    (is (= :pause (:intervention/type request)))
    (is (= :workflow (:intervention/target-type request)))
    (is (= workflow-id (:intervention/target-id request)))
    (is (= :proposed (:intervention/state request)))))

(deftest intervention-state-change-merges-onto-existing-record
  (let [intervention-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event (intervention-requested-event intervention-id
                                                                :pause
                                                                :workflow
                                                                (random-uuid)))
                  (acc/apply-event (intervention-state-changed-event
                                    intervention-id
                                    :applied
                                    {:intervention/outcome {:paused true}
                                     :intervention/reason "operator confirmed"})))
        request (get-in table [:interventions intervention-id])]
    (is (= :applied (:intervention/state request)))
    (is (= {:paused true} (:intervention/outcome request)))
    (is (= "operator confirmed" (:intervention/reason request)))
    (is (= :pause (:intervention/type request))
        "state-change events preserve creation fields from the request record")))

(deftest intervention-upserted-applies-baseline
  (let [intervention-id (random-uuid)
        entity {:intervention/id intervention-id
                :intervention/type :waive
                :intervention/target-type :policy-eval
                :intervention/target-id (random-uuid)
                :intervention/requested-by "operator@example.com"
                :intervention/request-source :dashboard
                :intervention/state :verified
                :intervention/requested-at (java.util.Date.)
                :intervention/updated-at (java.util.Date.)}
        table (acc/apply-event schema/empty-table
                               (ev :supervisory/intervention-upserted
                                   {:supervisory/entity entity}))]
    (is (= entity (get-in table [:interventions intervention-id])))))

;------------------------------------------------------------------------------ PolicyEvaluation

(deftest gate-passed-creates-evaluation
  (let [gate-id    (random-uuid)
        eval-id    (random-uuid)
        table (acc/apply-event schema/empty-table
                               (merge (ev :gate/passed
                                          {:gate/id gate-id
                                           :gate/target-type :pr
                                           :gate/target-id   ["acme/widget" 42]
                                           :gate/packs       ["core"]})
                                      {:event/id eval-id}))
        ev*   (get-in table [:policy-evals eval-id])]
    (is (true? (:policy-eval/passed? ev*)))
    (is (= gate-id (:policy-eval/gate-id ev*)))
    (is (= :pr (:policy-eval/target-type ev*)))
    (is (= ["core"] (:policy-eval/packs-applied ev*)))))

(deftest gate-failed-captures-violations
  (let [gate-id :review-approved
        eval-id (random-uuid)
        wf-id (random-uuid)
        table (acc/apply-event schema/empty-table
                               (merge (ev :gate/failed
                                          {:gate/id gate-id
                                           :workflow/id wf-id
                                           :gate/target-type :workflow-output
                                           :gate/target-id "bundle-42"
                                           :gate/violations [{:rule-id :no-force-push
                                                              :severity :critical
                                                              :category :security
                                                              :message "force push detected"
                                                              :remediable? false}]})
                                      {:event/id eval-id}))
        ev*   (get-in table [:policy-evals eval-id])]
    (is (false? (:policy-eval/passed? ev*)))
    (is (= wf-id (:policy-eval/workflow-run-id ev*)))
    (is (= :review-approved (:policy-eval/gate-id ev*)))
    (is (= :workflow-output (:policy-eval/target-type ev*)))
    (is (= "bundle-42" (:policy-eval/target-id ev*)))
    (is (= 1 (count (:policy-eval/violations ev*))))
    (is (= :no-force-push
           (-> ev* :policy-eval/violations first :violation/rule-id)))))

(deftest repeated-gate-evaluations-remain-distinct-records
  (let [gate-id :review-approved
        first-eval-id (random-uuid)
        second-eval-id (random-uuid)
        workflow-id (random-uuid)
        table (-> schema/empty-table
                  (acc/apply-event
                   (merge (ev :gate/failed
                              {:gate/id gate-id
                               :workflow/id workflow-id
                               :gate/violations [{:rule-id :first-rule
                                                  :severity :critical
                                                  :category :process
                                                  :message "first failure"}]})
                          {:event/id first-eval-id}))
                  (acc/apply-event
                   (merge (ev :gate/failed
                              {:gate/id gate-id
                               :workflow/id workflow-id
                               :gate/violations [{:rule-id :second-rule
                                                  :severity :high
                                                  :category :process
                                                  :message "second failure"}]})
                          {:event/id second-eval-id})))
        first-eval (get-in table [:policy-evals first-eval-id])
        second-eval (get-in table [:policy-evals second-eval-id])]
    (is (= 2 (count (:policy-evals table))))
    (is (= gate-id (:policy-eval/gate-id first-eval)))
    (is (= gate-id (:policy-eval/gate-id second-eval)))
    (is (= :first-rule
           (-> first-eval :policy-eval/violations first :violation/rule-id)))
    (is (= :second-rule
           (-> second-eval :policy-eval/violations first :violation/rule-id)))))

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
