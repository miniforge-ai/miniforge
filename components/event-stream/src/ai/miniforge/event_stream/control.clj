(ns ai.miniforge.event-stream.control
  "Structured control actions with RBAC for N8 OCI compliance.

   Control actions are commands issued by authorized listeners to affect
   workflow execution (pause, resume, retry, rollback, etc.). Each action
   carries requester identity, justification, and authorization context.

   RBAC roles define which action types are permitted per target category."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.approval :as approval]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; RBAC role definitions

(def default-roles
  "Default RBAC roles mapping role -> target-category -> permitted actions."
  {:operator {:workflows #{:pause :resume :retry :cancel}
              :agents #{:quarantine :adjust-budget}
              :fleet #{}
              :approvals #{}}
   :admin {:workflows #{:pause :resume :retry :cancel :rollback}
           :agents #{:quarantine :adjust-budget}
           :fleet #{:emergency-stop :drain}
           :approvals #{:gate-override :budget-escalation}}})

(def target-categories
  "Valid target types for control actions."
  #{:workflow :agent :fleet})

;------------------------------------------------------------------------------ Layer 1
;; Control action creation

(defn create-control-action
  "Create a structured control action.

   Arguments:
   - action-type: Keyword — :pause, :resume, :retry, :rollback, :cancel,
                  :quarantine, :adjust-budget, :emergency-stop, :gate-override
   - target: Map with :target-type (:workflow, :agent, :fleet) and :target-id
   - requester: Map with :principal (string), :role (keyword), :listener-id (optional)
   - opts: Optional map with :justification (string) and :parameters (map)

   Returns: Control action map."
  [action-type target requester & [opts]]
  (cond-> {:action/id (random-uuid)
           :action/type action-type
           :action/target target
           :action/requester requester
           :action/status :pending
           :action/created-at (java.util.Date.)}
    (:justification opts) (assoc :action/justification (:justification opts))
    (:parameters opts) (assoc :action/parameters (:parameters opts))))

;------------------------------------------------------------------------------ Layer 1
;; RBAC authorization

(def ^:private target-type->category
  "Map from target type to RBAC category keyword."
  {:workflow :workflows
   :agent    :agents
   :fleet    :fleet})

(defn authorize-action
  "Check RBAC authorization for a control action.

   Arguments:
   - roles: RBAC role definitions map (use default-roles or custom)
   - action: Control action map from create-control-action
   - requester: Map with :role keyword

   Returns: {:authorized? bool :reason string}"
  [roles action requester]
  (let [role (:role requester)
        role-perms (get roles role)
        target-type (get-in action [:action/target :target-type])
        category (target-type->category target-type)
        action-type (:action/type action)
        permitted-actions (get role-perms category #{})]
    (cond
      (nil? role-perms)
      {:authorized? false
       :reason (str "Unknown role: " role)
       :anomaly (response/make-anomaly :anomalies/not-found
                                        (str "Unknown role: " role)
                                        {:role role})}

      (nil? category)
      {:authorized? false
       :reason (str "Unknown target type: " target-type)
       :anomaly (response/make-anomaly :anomalies/incorrect
                                        (str "Unknown target type: " target-type)
                                        {:target-type target-type})}

      (contains? permitted-actions action-type)
      {:authorized? true :reason "Action permitted by role"}

      :else
      {:authorized? false
       :reason (str "Role " (name role) " cannot perform " (name action-type)
                    " on " (name target-type))
       :anomaly (response/make-anomaly :anomalies/forbidden
                                        (str "Role " (name role) " cannot perform "
                                             (name action-type) " on " (name target-type))
                                        {:role role
                                         :action-type action-type
                                         :target-type target-type})})))

;------------------------------------------------------------------------------ Layer 2
;; Control action execution

(defn execute-control-action!
  "Execute an authorized control action.

   Arguments:
   - stream: Event stream atom
   - action: Control action map
   - execution-fn: (fn [action] -> result-map) that performs the actual action

   Emits :control-action/requested before execution and
   :control-action/executed after. Returns result map with :status and :result."
  [stream action execution-fn]
  (let [workflow-id (get-in action [:action/target :target-id])
        action-id (:action/id action)]
    ;; Emit requested event
    (core/publish! stream
                   (core/control-action-requested
                    stream workflow-id action-id (:action/type action)
                    (:action/requester action)))
    ;; Execute
    (let [result (try
                   (let [r (execution-fn action)]
                     (response/success r))
                   (catch Exception e
                     (response/failure (.getMessage e) {:data (ex-data e)})))]
      ;; Emit executed event
      (core/publish! stream
                     (core/control-action-executed
                      stream workflow-id action-id result))
      result)))

;------------------------------------------------------------------------------ Layer 2
;; Approval-aware control action execution

(def ^:private actions-requiring-approval
  "Action types that require multi-party approval before execution."
  #{:gate-override :budget-escalation})

(defn requires-approval?
  "Check if a control action type requires multi-party approval."
  [action-type]
  (contains? actions-requiring-approval action-type))

(defn execute-control-action-with-approval!
  "Execute a control action, checking approval requirements first.

   If the action type requires approval (:gate-override, :budget-escalation),
   creates an approval request and returns {:status :awaiting-approval}.
   Otherwise delegates to execute-control-action!.

   Arguments:
   - stream: Event stream atom
   - action: Control action map
   - execution-fn: (fn [action] -> result-map)
   - approval-opts: Map with :required-signers, :quorum, :approval-manager

   Returns: Result map with :status key."
  [stream action execution-fn & [approval-opts]]
  (if (requires-approval? (:action/type action))
    (let [action-id (:action/id action)
          signers (get approval-opts :required-signers ["admin"])
          quorum (get approval-opts :quorum 1)
          mgr (get approval-opts :approval-manager)
          req (approval/create-approval-request action-id signers quorum)
          workflow-id (get-in action [:action/target :target-id])]
      ;; Store in manager if provided
      (when mgr
        (approval/store-approval! mgr req))
      ;; Emit approval requested event
      (core/publish! stream
                     (approval/approval-requested
                      stream workflow-id (:approval/id req)
                      action-id signers))
      {:status :awaiting-approval
       :approval/id (:approval/id req)
       :approval/required-signers signers
       :approval/quorum quorum})
    ;; No approval needed — execute directly
    (execute-control-action! stream action execution-fn)))
