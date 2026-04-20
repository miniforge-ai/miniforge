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

(ns ai.miniforge.agent.meta.progress-monitor
  "Progress Monitor meta-agent implementation.

   Watches streaming activity and file writes to detect stagnation."
  (:require [ai.miniforge.agent.meta-protocol :as mp]
            [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Default monitoring intervals
;;
;; All values are milliseconds. Operationally tuned: kept as named
;; constants here (vs an EDN file) since callers can override via the
;; create-progress-monitor-agent options map and there are only three
;; numbers to track.

(def ^:const default-check-interval-ms
  "How often the meta-agent runs its health check."
  30000)                                ; 30 seconds

(def ^:const default-stagnation-threshold-ms
  "Time without streaming activity or file writes that triggers a
   stagnation halt."
  120000)                               ; 2 minutes

(def ^:const default-max-total-ms
  "Hard upper bound on a single workflow run before forced halt."
  600000)                               ; 10 minutes

(defrecord ProgressMonitorMetaAgent [monitor-state config]
  mp/MetaAgent

  (check-health [_ workflow-state]
    (let [;; Record any new streaming chunks
          _ (doseq [chunk (:workflow/streaming-activity workflow-state)]
              (llm/record-chunk! monitor-state chunk))

          ;; Record any new file writes
          _ (doseq [file (:workflow/files-written workflow-state)]
              (llm/record-file-write! monitor-state file))

          ;; Check for timeout
          timeout-check (llm/check-timeout monitor-state)]

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
        (let [stats (llm/get-stats monitor-state)]
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
            (llm/create-progress-monitor
             (or (:monitor-options config)
                 {:stagnation-threshold-ms default-stagnation-threshold-ms
                  :max-total-ms            default-max-total-ms})))))

(defn create-progress-monitor-agent
  "Create a Progress Monitor meta-agent.

   Options:
   - :check-interval-ms        (default: default-check-interval-ms)        - How often to check
   - :priority                 (default: :high)                             - Check priority
   - :stagnation-threshold-ms  (default: default-stagnation-threshold-ms)   - Stagnation timeout
   - :max-total-ms             (default: default-max-total-ms)              - Hard timeout limit"
  ([]
   (create-progress-monitor-agent {}))
  ([{:keys [check-interval-ms priority stagnation-threshold-ms max-total-ms]
     :or {check-interval-ms       default-check-interval-ms
          priority                :high
          stagnation-threshold-ms default-stagnation-threshold-ms
          max-total-ms            default-max-total-ms}}]
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
      (atom (llm/create-progress-monitor monitor-options))
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
