(ns ai.miniforge.agent.meta-protocol
  "Protocol and interface for meta-agents that monitor and control workflows.

   Meta-agents are specialized agents that observe workflow execution and can
   halt the factory floor when they detect issues. Unlike regular agents that
   produce artifacts, meta-agents produce decisions about workflow health.

   Design Philosophy:
   - Any meta-agent can stop the workflow (distributed authority)
   - Each meta-agent has focused domain expertise
   - Decisions are observable and logged
   - Lightweight coordinator routes information, doesn't control")

(defprotocol MetaAgent
  "Protocol for meta-agents that monitor workflow execution.

   Meta-agents receive workflow state updates and decide whether
   the workflow should continue or halt."

  (check-health [this workflow-state]
    "Check workflow health and return a decision.

     Arguments:
     - workflow-state: Current workflow state map including:
       {:workflow/id UUID
        :workflow/phase :plan|:implement|:verify|:review|:release
        :workflow/iterations number
        :workflow/artifacts {...}
        :workflow/metrics {...}
        :workflow/streaming-activity [...]
        :workflow/files-written [...]}

     Returns:
     {:status :healthy|:warning|:halt
      :agent/id keyword (e.g., :progress-monitor)
      :message string (human-readable explanation)
      :data {} (diagnostic data)
      :checked-at inst}

     Status meanings:
     - :healthy - Workflow is progressing normally
     - :warning - Issue detected but not critical yet
     - :halt - Workflow must stop immediately")

  (get-meta-config [this]
    "Get meta-agent configuration.

     Returns:
     {:agent/id :progress-monitor
      :agent/name \"Progress Monitor\"
      :agent/can-halt? true
      :agent/check-interval-ms 30000
      :agent/priority :high|:medium|:low}")

  (reset-state! [this]
    "Reset meta-agent internal state. Called when workflow restarts."))

(defn healthy?
  "Check if health status indicates workflow can continue."
  [health-check]
  (= :healthy (:status health-check)))

(defn halt?
  "Check if health status indicates workflow must stop."
  [health-check]
  (= :halt (:status health-check)))

(defn warning?
  "Check if health status indicates a warning."
  [health-check]
  (= :warning (:status health-check)))

(defn create-health-check
  "Helper to create a health check result.

   Usage:
   (create-health-check :progress-monitor :healthy \"Making progress\")
   (create-health-check :test-quality :halt \"No tests found\" {:files-checked 10})"
  ([agent-id status message]
   (create-health-check agent-id status message nil))
  ([agent-id status message data]
   {:status status
    :agent/id agent-id
    :message message
    :data data
    :checked-at (java.time.Instant/now)}))

(defrecord MetaAgentConfig
  [id                    ; Keyword ID (:progress-monitor, :test-quality, etc.)
   name                  ; Human-readable name
   can-halt?             ; Can this agent halt the workflow?
   check-interval-ms     ; How often to run health checks
   priority              ; :high, :medium, :low - affects check ordering
   enabled?])            ; Is this meta-agent active?

(defn create-meta-config
  "Create a meta-agent configuration.

   Options:
   - :id (required) - Keyword identifier
   - :name (required) - Human-readable name
   - :can-halt? (default: true) - Can halt workflow
   - :check-interval-ms (default: 30000) - Check frequency
   - :priority (default: :medium) - :high, :medium, :low
   - :enabled? (default: true) - Is active"
  [{:keys [id name can-halt? check-interval-ms priority enabled?]
    :or {can-halt? true
         check-interval-ms 30000
         priority :medium
         enabled? true}
    :as opts}]
  (when-not (and id name)
    (throw (ex-info "Meta-agent config requires :id and :name"
                    {:provided opts})))
  (map->MetaAgentConfig
   {:id id
    :name name
    :can-halt? can-halt?
    :check-interval-ms check-interval-ms
    :priority priority
    :enabled? enabled?}))

(comment
  ;; Example meta-agent implementation
  #_(defrecord ProgressMonitorAgent [monitor-state]
    MetaAgent
    (check-health [this workflow-state]
      (let [timeout-check (check-timeout @monitor-state)]
        (if timeout-check
          (create-health-check
           :progress-monitor
           :halt
           (:message timeout-check)
           (:stats timeout-check))
          (create-health-check
           :progress-monitor
           :healthy
           "Workflow making progress"
           (get-stats @monitor-state)))))

    (get-meta-config [this]
      (create-meta-config
       {:id :progress-monitor
        :name "Progress Monitor"
        :can-halt? true
        :check-interval-ms 30000
        :priority :high}))

    (reset-state! [this]
      (reset! monitor-state (create-progress-monitor {})))))
