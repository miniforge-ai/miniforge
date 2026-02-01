(ns ai.miniforge.agent.meta.progress-monitor
  "Progress Monitor meta-agent implementation.

   Watches streaming activity and file writes to detect stagnation."
  (:require [ai.miniforge.agent.meta-protocol :as mp]
            [ai.miniforge.llm.progress-monitor :as pm]))

(defrecord ProgressMonitorMetaAgent [monitor-state config]
  mp/MetaAgent

  (check-health [_ workflow-state]
    (let [;; Record any new streaming chunks
          _ (doseq [chunk (:workflow/streaming-activity workflow-state)]
              (pm/record-chunk! monitor-state chunk))

          ;; Record any new file writes
          _ (doseq [file (:workflow/files-written workflow-state)]
              (pm/record-file-write! monitor-state file))

          ;; Check for timeout
          timeout-check (pm/check-timeout monitor-state)]

      (if timeout-check
        ;; Timeout detected - halt workflow
        (mp/create-health-check
         :progress-monitor
         :halt
         (:message timeout-check)
         (merge (:stats timeout-check)
                {:timeout-type (:type timeout-check)
                 :elapsed-ms (:elapsed-ms timeout-check)}))

        ;; Still healthy - provide current stats
        (let [stats (pm/get-stats monitor-state)]
          (if (:is-active? stats)
            (mp/create-health-check
             :progress-monitor
             :healthy
             (format "Workflow active: %d chunks, %d files"
                     (:chunks stats)
                     (:files-written stats))
             stats)
            ;; Not active but not timed out yet - warning
            (mp/create-health-check
             :progress-monitor
             :warning
             (format "No activity for %dms"
                     (:time-since-activity-ms stats))
             stats))))))

  (get-meta-config [_]
    config)

  (reset-state! [_]
    (reset! monitor-state
            (pm/create-progress-monitor
             (or (:monitor-options config)
                 {:stagnation-threshold-ms 120000  ; 2 minutes
                  :max-total-ms 600000})))))       ; 10 minutes

(defn create-progress-monitor-agent
  "Create a Progress Monitor meta-agent.

   Options:
   - :check-interval-ms (default: 30000) - How often to check
   - :priority (default: :high) - Check priority
   - :stagnation-threshold-ms (default: 120000) - Stagnation timeout
   - :max-total-ms (default: 600000) - Hard timeout limit"
  ([]
   (create-progress-monitor-agent {}))
  ([{:keys [check-interval-ms priority stagnation-threshold-ms max-total-ms]
     :or {check-interval-ms 30000
          priority :high
          stagnation-threshold-ms 120000
          max-total-ms 600000}}]
   (let [monitor-options {:stagnation-threshold-ms stagnation-threshold-ms
                          :max-total-ms max-total-ms}
         config (mp/create-meta-config
                 {:id :progress-monitor
                  :name "Progress Monitor"
                  :can-halt? true
                  :check-interval-ms check-interval-ms
                  :priority priority
                  :enabled? true
                  :monitor-options monitor-options})]
     (->ProgressMonitorMetaAgent
      (atom (pm/create-progress-monitor monitor-options))
      config))))

(comment
  ;; Usage
  (def agent (create-progress-monitor-agent
              {:stagnation-threshold-ms 60000   ; 1 minute
               :max-total-ms 300000}))          ; 5 minutes

  ;; Check health
  (def workflow-state
    {:workflow/streaming-activity ["Analyzing code..."
                                    "Creating plan..."]
     :workflow/files-written ["src/foo.clj"]})

  (mp/check-health agent workflow-state)
  ;; => {:status :healthy
  ;;     :agent/id :progress-monitor
  ;;     :message "Workflow active: 2 chunks, 1 files"
  ;;     :data {...}})
)
