(ns ai.miniforge.agent.meta-coordinator
  "Coordinator for meta-agent team.

   The coordinator is a lightweight message router, NOT a controller.
   It routes workflow state to meta-agents and aggregates their decisions.
   Any meta-agent can halt the workflow - coordinator just enforces it."
  (:require [ai.miniforge.agent.meta-protocol :as mp]
            [ai.miniforge.response.interface :as response]))

(defrecord MetaCoordinator
  [agents           ; Vector of MetaAgent instances
   last-checks      ; Atom of {agent-id -> last-check-result}
   check-history])  ; Atom of [{:timestamp :agent-id :result}...]

(defn create-coordinator
  "Create a meta-agent coordinator.

   Arguments:
   - agents: Vector of MetaAgent protocol instances

   Returns: MetaCoordinator record"
  [agents]
  (->MetaCoordinator
   agents
   (atom {})
   (atom [])))

(defn should-check-agent?
  "Determine if an agent should be checked now based on interval."
  [agent last-check-time]
  (if-not last-check-time
    true  ; Never checked, do it now
    (let [config (mp/get-meta-config agent)
          interval (:check-interval-ms config)
          now (System/currentTimeMillis)
          elapsed (- now last-check-time)]
      (>= elapsed interval))))

(defn record-check!
  "Record a health check result."
  [coordinator agent-id result]
  (let [now (System/currentTimeMillis)]
    ;; Update last check time
    (swap! (:last-checks coordinator) assoc agent-id now)
    ;; Add to history (keep last 1000)
    (swap! (:check-history coordinator)
           (fn [history]
             (let [entry {:timestamp now
                          :agent-id agent-id
                          :result result}]
               (take 1000 (cons entry history)))))
    result))

(defn check-all-agents
  "Run health checks on all enabled agents that are due for checking.

   Arguments:
   - coordinator: MetaCoordinator instance
   - workflow-state: Current workflow state

   Returns:
   {:status :healthy|:warning|:halt
    :checks [{:agent-id :progress-monitor :status :healthy ...}]
    :halt-reason \"...\" (if status is :halt)
    :halting-agent :progress-monitor (if status is :halt)}"
  [coordinator workflow-state]
  (let [agents (:agents coordinator)
        last-checks @(:last-checks coordinator)

        ;; Run checks on agents that are due
        checks (for [agent agents
                     :let [config (mp/get-meta-config agent)]
                     :when (:enabled? config)
                     :when (should-check-agent? agent (get last-checks (:id config)))]
                 (try
                   (let [result (mp/check-health agent workflow-state)]
                     (record-check! coordinator (:id config) result)
                     result)
                   (catch Exception e
                     ;; Return warning status on error - logging handled by caller if needed
                     (mp/create-health-check
                      (:id config)
                      :warning
                      (str "Health check error: " (ex-message e))
                      {:error (ex-data e)}))))

        ;; Find any halt signals
        halt-check (first (filter mp/halt? checks))

        ;; Find any warnings
        warning-checks (filter mp/warning? checks)

        ;; Aggregate status
        overall-status (cond
                         halt-check :halt
                         (seq warning-checks) :warning
                         :else :healthy)]

    (response/status-check overall-status
                           (cond-> {:checks checks}
                             halt-check (assoc :halt-reason (:message halt-check)
                                               :halting-agent (:agent/id halt-check))
                             (seq warning-checks) (assoc :warnings (mapv :message warning-checks))))))

(defn reset-all-agents!
  "Reset state of all meta-agents. Called when workflow restarts."
  [coordinator]
  (doseq [agent (:agents coordinator)]
    (try
      (mp/reset-state! agent)
      (catch Exception _e
        ;; Silently continue on reset errors - caller can check if needed
        nil)))
  (reset! (:last-checks coordinator) {})
  coordinator)

(defn get-check-history
  "Get recent health check history.

   Options:
   - :limit - Max number of checks to return (default: 100)
   - :agent-id - Filter by specific agent
   - :status - Filter by status (:healthy, :warning, :halt)"
  ([coordinator]
   (get-check-history coordinator {}))
  ([coordinator {:keys [limit agent-id status]
                 :or {limit 100}}]
   (cond->> @(:check-history coordinator)
     agent-id (filter #(= agent-id (:agent-id %)))
     status (filter #(= status (get-in % [:result :status])))
     limit (take limit))))

(defn get-agent-stats
  "Get statistics for all meta-agents.

   Returns:
   {:agents [{:id :progress-monitor
              :name \"Progress Monitor\"
              :checks-run 42
              :last-check inst
              :last-status :healthy
              :halt-count 2
              :warning-count 5}]}"
  [coordinator]
  (let [history @(:check-history coordinator)
        agents (:agents coordinator)]
    {:agents
     (for [agent agents
           :let [config (mp/get-meta-config agent)
                 agent-id (:id config)
                 agent-checks (filter #(= agent-id (:agent-id %)) history)
                 last-check (first agent-checks)]]
       {:id agent-id
        :name (:name config)
        :enabled? (:enabled? config)
        :can-halt? (:can-halt? config)
        :checks-run (count agent-checks)
        :last-check (:timestamp last-check)
        :last-status (get-in last-check [:result :status])
        :halt-count (count (filter #(= :halt (get-in % [:result :status])) agent-checks))
        :warning-count (count (filter #(= :warning (get-in % [:result :status])) agent-checks))})}))

(defn add-agent!
  "Dynamically add a meta-agent to the coordinator.

   Returns: Updated coordinator"
  [coordinator agent]
  (update coordinator :agents conj agent))

(defn remove-agent!
  "Remove a meta-agent by ID.

   Returns: Updated coordinator"
  [coordinator agent-id]
  (update coordinator :agents
          (fn [agents]
            (vec (remove #(= agent-id (-> % mp/get-meta-config :id))
                         agents)))))

(comment
  ;; Usage example
  ;; (require '[ai.miniforge.agent.meta.progress-monitor :as pm])
  ;; (def progress-monitor (pm/create-progress-monitor-agent))
  ;; (def test-quality (create-test-quality-agent))  ; TODO: implement
  ;; (def conflict-detector (create-conflict-detector-agent))  ; TODO: implement

  ;; (def coordinator (create-coordinator
  ;;                   [progress-monitor test-quality conflict-detector]))

  ;; Check all agents
  ;; (def workflow-state
  ;;   {:workflow/id (random-uuid)
  ;;    :workflow/phase :implement
  ;;    :workflow/iterations 3
  ;;    :workflow/streaming-activity [...]
  ;;    :workflow/files-written [...]})

  ;; (def result (check-all-agents coordinator workflow-state))
  ;; ;; => {:status :healthy
  ;; ;;     :checks [{:agent/id :progress-monitor :status :healthy ...}
  ;; ;;              {:agent/id :test-quality :status :healthy ...}
  ;; ;;              {:agent/id :conflict-detector :status :halt ...}]
  ;; ;;     :halt-reason "Merge conflict detected"
  ;; ;;     :halting-agent :conflict-detector}

  ;; ;; If any agent signals halt, workflow stops
  ;; (when (= :halt (:status result))
  ;;   (println "Workflow halted by" (:halting-agent result))
  ;;   (println "Reason:" (:halt-reason result)))

  ;; ;; Get statistics
  ;; (get-agent-stats coordinator)

  ;; ;; Get history
  ;; (get-check-history coordinator {:limit 50 :agent-id :progress-monitor})
  )
