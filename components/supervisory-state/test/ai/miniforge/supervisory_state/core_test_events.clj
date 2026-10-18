;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.supervisory-state.core-test-events
  "Shared envelope factory for the supervisory lifecycle scenarios.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} event [type workflow-id message fields]
  (merge {:event/type type
          :event/id (random-uuid)
          :event/timestamp (java.util.Date.)
          :event/version "1.0.0"
          :event/sequence-number 0
          :workflow/id workflow-id
          :message message}
         fields))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} workflow-started [workflow-id]
  (event :workflow/started workflow-id "Workflow started" {}))

(defn ^{:stratum 1} workflow-completed [workflow-id]
  (event :workflow/completed workflow-id "Workflow completed" {:event/sequence-number 1}))

(defn ^{:stratum 1} task-state-changed [task-id workflow-id to-state & [context]]
  (event :task/state-changed workflow-id (str "Task " task-id " → " (name to-state))
         (cond-> {:task/id task-id :task/to-state to-state}
           context (assoc :task/context context))))

(defn ^{:stratum 1} cp-decision-created-event [decision-id agent-id summary]
  (event :control-plane/decision-created (random-uuid)
         (str "Decision needed from " agent-id ": " summary)
         {:cp/agent-id agent-id :cp/decision-id decision-id :cp/summary summary}))

(defn ^{:stratum 1} cp-decision-resolved-event [decision-id resolution]
  (event :control-plane/decision-resolved (random-uuid)
         (str "Decision " decision-id " resolved: " resolution)
         {:cp/decision-id decision-id :cp/resolution resolution}))

(defn ^{:stratum 1} intervention-requested-event [workflow-id intervention-id]
  (event :supervisory/intervention-requested workflow-id "Pause requested"
         {:intervention/id intervention-id
          :intervention/type :pause
          :intervention/target-type :workflow
          :intervention/target-id workflow-id
          :intervention/requested-by "operator@example.com"
          :intervention/request-source :tui
          :intervention/state :proposed
          :intervention/requested-at (java.util.Date.)
          :intervention/updated-at (java.util.Date.)}))

(defn ^{:stratum 1} intervention-state-changed-event [workflow-id intervention-id next-state]
  (event :supervisory/intervention-state-changed workflow-id
         (str "Intervention " intervention-id " → " (name next-state))
         {:intervention/id intervention-id
          :intervention/state next-state
          :intervention/outcome {:paused true}}))

(comment
  (workflow-started (random-uuid)))
