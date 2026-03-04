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
;; Control state management

(defn create-control-state
  "Create a canonical control-state atom for workflow execution.
   Used by both CLI dashboard poller and TUI to drive pause/resume/cancel."
  []
  (atom {:paused false :stopped false :adjustments {}}))

(defn pause!
  "Pause workflow execution."
  [control-state]
  (swap! control-state assoc :paused true))

(defn resume!
  "Resume paused workflow execution."
  [control-state]
  (swap! control-state assoc :paused false))

(defn cancel!
  "Cancel workflow execution."
  [control-state]
  (swap! control-state assoc :stopped true))

(defn paused?
  "Check if workflow is paused."
  [control-state]
  (:paused @control-state))

(defn cancelled?
  "Check if workflow is cancelled."
  [control-state]
  (:stopped @control-state))

;------------------------------------------------------------------------------ Layer 1a
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

;------------------------------------------------------------------------------ Layer 1b
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

;------------------------------------------------------------------------------ Layer 1c
;; RBAC authorization

(def ^:private target-type->category
  "Map from target type to RBAC category keyword."
  {:workflow :workflows
   :agent    :agents
   :fleet    :fleet})

(defn- authorization-granted
  "Build a granted authorization result."
  [reason]
  {:authorized? true :reason reason})

(defn- authorization-denied
  "Build a denied authorization result with anomaly."
  [anomaly-category message context]
  {:authorized? false
   :reason message
   :anomaly (response/make-anomaly anomaly-category message context)})

(defn authorize-action
  "Check RBAC authorization for a control action.

   Arguments:
   - roles: RBAC role definitions map (use default-roles or custom)
   - action: Control action map from create-control-action
   - requester: Map with :role keyword

   Returns: {:authorized? bool :reason string :anomaly map?}"
  [roles action requester]
  (let [role (:role requester)
        role-perms (get roles role)
        target-type (get-in action [:action/target :target-type])
        category (target-type->category target-type)
        action-type (:action/type action)
        permitted-actions (get role-perms category #{})]
    (cond
      (nil? role-perms)
      (authorization-denied :anomalies/not-found
                            (str "Unknown role: " role)
                            {:role role})

      (nil? category)
      (authorization-denied :anomalies/incorrect
                            (str "Unknown target type: " target-type)
                            {:target-type target-type})

      (contains? permitted-actions action-type)
      (authorization-granted "Action permitted by role")

      :else
      (authorization-denied :anomalies/forbidden
                            (str "Role " (name role) " cannot perform "
                                 (name action-type) " on " (name target-type))
                            {:role role
                             :action-type action-type
                             :target-type target-type}))))

;------------------------------------------------------------------------------ Layer 2a
;; Control action execution

(defn execute-control-action!
  "Execute a control action with RBAC authorization.

   Calls authorize-action before executing. Returns authorization failure
   if the requester lacks permission.

   Arguments:
   - stream: Event stream atom
   - action: Control action map
   - execution-fn: (fn [action] -> result-map) that performs the actual action
   - opts: Optional map with :roles (RBAC roles, defaults to default-roles)

   Emits :control-action/requested before execution and
   :control-action/executed after. Returns result map with :status and :result."
  [stream action execution-fn & [opts]]
  (let [workflow-id (get-in action [:action/target :target-id])
        action-id (:action/id action)
        roles (get opts :roles default-roles)
        requester (:action/requester action)
        auth-result (authorize-action roles action requester)]
    (if-not (:authorized? auth-result)
      ;; RBAC denied
      (do
        (core/publish! stream
                       (core/control-action-requested
                        stream workflow-id action-id (:action/type action)
                        requester))
        (let [denial {:status :denied
                      :reason (:reason auth-result)
                      :anomaly (:anomaly auth-result)}]
          (core/publish! stream
                         (core/control-action-executed
                          stream workflow-id action-id denial))
          denial))
      ;; RBAC authorized — execute
      (do
        (core/publish! stream
                       (core/control-action-requested
                        stream workflow-id action-id (:action/type action)
                        requester))
        (let [result (try
                       (let [r (execution-fn action)]
                         (response/success r))
                       (catch Exception e
                         (response/failure (.getMessage e) {:data (ex-data e)})))]
          (core/publish! stream
                         (core/control-action-executed
                          stream workflow-id action-id result))
          result)))))

;------------------------------------------------------------------------------ Layer 2b
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
