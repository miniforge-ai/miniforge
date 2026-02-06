(ns ai.miniforge.reporting.core
  "Core reporting implementation."
  (:require
   [ai.miniforge.reporting.protocol :as proto]
   [ai.miniforge.workflow.interface :as wf]
   [ai.miniforge.operator.interface :as op]
   [ai.miniforge.artifact.interface :as art]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Helper functions

(defn- safe-get
  "Safely get value from component, returning nil on error."
  [f & args]
  (try
    (apply f args)
    (catch Exception _e
      nil)))

(defn- count-by-status
  "Count workflows by status."
  [workflows status]
  (count (filter #(= status (:workflow/status %)) workflows)))

;; Note: Keep for future phase-based filtering
#_(defn- count-by-phase
    "Count workflows by phase."
    [workflows phase]
    (count (filter #(= phase (:workflow/phase %)) workflows)))

;------------------------------------------------------------------------------ Layer 1
;; Subscription management (polling-based for BB compatibility)

(defn- create-subscription
  "Create a new subscription record."
  [topics callback]
  {:subscription/id (random-uuid)
   :subscription/topics (set topics)
   :subscription/callback callback
   :subscription/created-at (System/currentTimeMillis)
   :subscription/last-poll 0
   :subscription/event-queue (atom [])})

;; Note: Keep for future event broadcasting support
#_(defn- add-event-to-subscriptions
    "Add event to relevant subscriptions."
    [subscriptions event]
    (doseq [[_id sub] @subscriptions]
      (when (contains? (:subscription/topics sub) (:event/topic event))
        (swap! (:subscription/event-queue sub) conj event))))

;------------------------------------------------------------------------------ Layer 2
;; System status aggregation

(defn- aggregate-workflow-stats
  "Aggregate workflow statistics."
  [workflow-component]
  (if-let [all-workflows (safe-get wf/get-state workflow-component :all)]
    {:active (count-by-status all-workflows :running)
     :pending (count-by-status all-workflows :pending)
     :completed (count-by-status all-workflows :completed)
     :failed (count-by-status all-workflows :failed)}
    {:active 0 :pending 0 :completed 0 :failed 0}))

(defn- aggregate-resource-metrics
  "Aggregate resource usage metrics."
  [workflow-component]
  (if-let [all-workflows (safe-get wf/get-state workflow-component :all)]
    (reduce
     (fn [acc wf]
       (let [metrics (:workflow/metrics wf {})]
         {:tokens-used (+ (:tokens-used acc 0) (:tokens metrics 0))
          :cost-usd (+ (:cost-usd acc 0.0) (:cost-usd metrics 0.0))}))
     {:tokens-used 0 :cost-usd 0.0}
     all-workflows)
    {:tokens-used 0 :cost-usd 0.0}))

(defn- aggregate-meta-loop-status
  "Aggregate meta-loop status."
  [operator-component]
  (if operator-component
    (let [proposals (safe-get op/get-proposals operator-component {:status :proposed})
          pending-count (count proposals)]
      {:status (if (> pending-count 0) :active :idle)
       :pending-improvements pending-count})
    {:status :not-configured
     :pending-improvements 0}))

(defn- collect-alerts
  "Collect system alerts."
  [workflow-stats operator-component]
  (let [alerts []]
    (cond-> alerts
      (> (:failed workflow-stats 0) 0)
      (conj {:type :failed-workflows
             :severity :error
             :message (str (:failed workflow-stats) " failed workflow(s)")})
      
      (and operator-component
           (> (count (safe-get op/get-proposals operator-component {:status :proposed})) 5))
      (conj {:type :pending-improvements
             :severity :warning
             :message "Multiple pending improvements awaiting review"}))))

;------------------------------------------------------------------------------ Layer 3
;; Workflow detail aggregation

(defn- build-workflow-timeline
  "Build timeline of phase transitions."
  [workflow-state]
  (let [history (:workflow/history workflow-state [])]
    (mapv (fn [entry]
            {:phase (:phase entry)
             :status (:status entry)
             :started-at (:started-at entry)
             :completed-at (:completed-at entry)})
          history)))

(defn- get-workflow-artifacts
  "Get artifacts for a workflow."
  [artifact-store workflow-id]
  (if artifact-store
    (let [artifacts (safe-get art/query artifact-store {:workflow-id workflow-id})]
      (mapv (fn [art]
              {:id (:artifact/id art)
               :type (:artifact/type art)
               :created-at (:artifact/created-at art)})
            (or artifacts [])))
    []))

(defn- get-workflow-logs
  "Get logs for a workflow."
  [logger _workflow-id]
  (if logger
    ;; In real implementation, would query logger with context filter
    ;; For now, return empty as logger doesn't expose query API
    []
    []))

;------------------------------------------------------------------------------ Layer 4
;; ReportingService implementation

(defrecord ReportingServiceImpl [workflow-component
                                  orchestrator-component
                                  operator-component
                                  artifact-store
                                  logger
                                  subscriptions
                                  config]
  proto/ReportingService

  (get-system-status [_this]
    (let [workflow-stats (aggregate-workflow-stats workflow-component)
          resource-metrics (aggregate-resource-metrics workflow-component)
          meta-loop-status (aggregate-meta-loop-status operator-component)
          alerts (collect-alerts workflow-stats operator-component)]
      {:workflows workflow-stats
       :resources resource-metrics
       :meta-loop meta-loop-status
       :alerts alerts}))

  (get-workflow-list [_this criteria]
    (let [status-filter (:status criteria)
          phase-filter (:phase criteria)
          limit (:limit criteria 100)
          all-workflows (if workflow-component
                          (safe-get wf/get-state workflow-component :all)
                          [])]
      (->> (or all-workflows [])
           (filter (fn [wf]
                     (and (or (nil? status-filter)
                              (= status-filter (:workflow/status wf)))
                          (or (nil? phase-filter)
                              (= phase-filter (:workflow/phase wf))))))
           (take limit)
           (mapv (fn [wf]
                   {:workflow/id (:workflow/id wf)
                    :workflow/status (:workflow/status wf)
                    :workflow/phase (:workflow/phase wf)
                    :workflow/created-at (:workflow/created-at wf)})))))

  (get-workflow-detail [_this workflow-id]
    (if-let [workflow-state (safe-get wf/get-state workflow-component workflow-id)]
      {:header {:id (:workflow/id workflow-state)
                :status (:workflow/status workflow-state)
                :phase (:workflow/phase workflow-state)
                :created-at (:workflow/created-at workflow-state)
                :updated-at (:workflow/updated-at workflow-state)}
       :timeline (build-workflow-timeline workflow-state)
       :current-task {:description (:workflow/current-task workflow-state)
                      :agent (:workflow/current-agent workflow-state)
                      :status (:workflow/status workflow-state)}
       :artifacts (get-workflow-artifacts artifact-store workflow-id)
       :logs (get-workflow-logs logger workflow-id)}
      nil))

  (get-meta-loop-status [_this]
    (if operator-component
      (let [signals (safe-get op/get-signals operator-component {:limit 10})
            pending (safe-get op/get-proposals operator-component {:status :proposed})
            recent (safe-get op/get-proposals operator-component {:status :applied})]
        {:signals (or signals [])
         :pending-improvements (or pending [])
         :recent-improvements (take 10 (or recent []))})
      {:signals []
       :pending-improvements []
       :recent-improvements []}))

  (subscribe [_this topics callback]
    (let [sub (create-subscription topics callback)
          sub-id (:subscription/id sub)]
      (swap! subscriptions assoc sub-id sub)
      (when logger
        (log/debug logger :reporting :reporting/subscription-created
                   {:data {:subscription-id sub-id :topics topics}}))
      sub-id))

  (unsubscribe [_this subscription-id]
    (let [existed? (contains? @subscriptions subscription-id)]
      (swap! subscriptions dissoc subscription-id)
      (when (and logger existed?)
        (log/debug logger :reporting :reporting/subscription-removed
                   {:data {:subscription-id subscription-id}}))
      existed?))

  (poll-events [_this subscription-id]
    (if-let [sub (get @subscriptions subscription-id)]
      (let [queue (:subscription/event-queue sub)
            events @queue
            callback (:subscription/callback sub)]
        ;; Reset queue and update last poll time
        (reset! queue [])
        (swap! subscriptions assoc-in [subscription-id :subscription/last-poll]
               (System/currentTimeMillis))
        ;; Invoke callback for each event
        (doseq [event events]
          (try
            (callback event)
            (catch Exception e
              (when logger
                (log/error logger :reporting :reporting/callback-error
                           {:data {:error (str e)}})))))
        events)
      [])))

;------------------------------------------------------------------------------ Layer 5
;; Constructor

(defn create-reporting-service
  "Create a reporting service.

   Options:
   - :workflow-component - Workflow component instance
   - :orchestrator-component - Orchestrator component instance
   - :operator-component - Operator component instance
   - :artifact-store - Artifact store instance
   - :logger - Logger instance

   Example:
     (create-reporting-service {:workflow-component wf
                                :operator-component op
                                :logger logger})"
  ([] (create-reporting-service {}))
  ([{:keys [workflow-component
            orchestrator-component
            operator-component
            artifact-store
            logger
            config]
     :or {config {}}}]
   (->ReportingServiceImpl
    workflow-component
    orchestrator-component
    operator-component
    artifact-store
    logger
    (atom {})  ; subscriptions
    config)))
