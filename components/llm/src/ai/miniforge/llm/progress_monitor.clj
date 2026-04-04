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

(ns ai.miniforge.llm.progress-monitor
   "Adaptive timeout monitoring based on actual progress detection.

   Instead of fixed timeouts, monitors streaming activity and file system
   changes to detect when an agent is stuck vs actively working."
   )

 (defn create-progress-monitor
   "Create a progress monitor for adaptive timeout.

   Options:
   - :stagnation-threshold-ms - Time without progress before considering stuck (default: 120000 = 2min)
   - :max-total-ms - Hard limit regardless of progress (default: 600000 = 10min)
   - :min-activity-interval-ms - Minimum time between progress signals (default: 5000 = 5sec)

   Returns monitor state atom."
   [{:keys [stagnation-threshold-ms max-total-ms min-activity-interval-ms]
     :or {stagnation-threshold-ms 120000  ; 2 minutes
          max-total-ms 600000              ; 10 minutes
          min-activity-interval-ms 5000}}] ; 5 seconds
   (atom {:started-at (System/currentTimeMillis)
          :last-activity-at (System/currentTimeMillis)
          :last-chunk-content nil
          :chunk-count 0
          :unique-chunks #{}
          :file-writes #{}
          :stagnation-threshold-ms stagnation-threshold-ms
          :max-total-ms max-total-ms
          :min-activity-interval-ms min-activity-interval-ms
          :stagnant-cycles 0}))

 (defn record-chunk!
   "Record a streaming chunk as activity.

   Returns true if this represents meaningful progress, false if stagnant."
   [monitor chunk-content]
   (let [now (System/currentTimeMillis)
         state @monitor
         last-content (:last-chunk-content state)
         last-activity (:last-activity-at state)
         min-interval (:min-activity-interval-ms state)
         is-different? (not= chunk-content last-content)
         is-not-just-thinking? (and chunk-content
                                    (not (re-find #"(?i)^(thinking|analyzing|considering)" chunk-content)))
         is-substantive? (and chunk-content (> (count chunk-content) 10))
         time-since-activity (- now last-activity)
         ;; First chunk always counts as progress (last-content will be nil)
         is-first-chunk? (nil? last-content)
         sufficient-interval? (or is-first-chunk?
                                  (> time-since-activity min-interval))

         meaningful-progress? (and is-different?
                                   is-not-just-thinking?
                                   is-substantive?
                                   sufficient-interval?)]

     (swap! monitor
            (fn [state]
              (cond-> (assoc state
                             :last-chunk-content chunk-content
                             :chunk-count (inc (:chunk-count state)))
                meaningful-progress?
                (assoc :last-activity-at now
                       :stagnant-cycles 0
                       :unique-chunks (conj (:unique-chunks state) chunk-content))

                (not meaningful-progress?)
                (update :stagnant-cycles inc))))

     meaningful-progress?))

(defn record-file-write!
  "Record a file write as significant progress."
  [monitor file-path]
  (let [now (System/currentTimeMillis)]
    (swap! monitor
           (fn [state]
             (assoc state
                    :last-activity-at now
                    :stagnant-cycles 0
                    :file-writes (conj (:file-writes state) file-path))))))

(defn check-timeout
  "Check if the monitor has timed out.

   Returns:
   - nil if still making progress
   - {:type :stagnation :elapsed-ms ...} if stuck without progress
   - {:type :hard-limit :elapsed-ms ...} if exceeded max time"
  [monitor]
  (let [now (System/currentTimeMillis)
        state @monitor
        {:keys [started-at last-activity-at stagnation-threshold-ms max-total-ms]} state
        total-elapsed (- now started-at)
        stagnation-elapsed (- now last-activity-at)]

    (cond
      ;; Hard limit exceeded
      (> total-elapsed max-total-ms)
      {:type :hard-limit
       :elapsed-ms total-elapsed
       :max-ms max-total-ms
       :message (format "Hard timeout: exceeded %dms limit" max-total-ms)
       :stats {:chunks (:chunk-count state)
               :unique-chunks (count (:unique-chunks state))
               :files-written (count (:file-writes state))
               :stagnant-cycles (:stagnant-cycles state)}}

      ;; Stagnation detected
      (> stagnation-elapsed stagnation-threshold-ms)
      {:type :stagnation
       :elapsed-ms stagnation-elapsed
       :threshold-ms stagnation-threshold-ms
       :message (format "Stagnation timeout: no progress for %dms" stagnation-elapsed)
       :stats {:chunks (:chunk-count state)
               :unique-chunks (count (:unique-chunks state))
               :files-written (count (:file-writes state))
               :stagnant-cycles (:stagnant-cycles state)}}

      ;; Still making progress
      :else nil)))

(defn get-stats
  "Get current statistics from the monitor."
  [monitor]
  (let [now (System/currentTimeMillis)
        state @monitor
        {:keys [started-at last-activity-at chunk-count unique-chunks file-writes stagnant-cycles]} state]
    {:total-elapsed-ms (- now started-at)
     :time-since-activity-ms (- now last-activity-at)
     :chunks chunk-count
     :unique-chunks (count unique-chunks)
     :files-written (count file-writes)
     :stagnant-cycles stagnant-cycles
     :is-active? (< (- now last-activity-at) 30000)})) ; Active if activity in last 30s

(comment
  ;; Usage example
  (def monitor (create-progress-monitor
                {:stagnation-threshold-ms 120000  ; 2 minutes
                 :max-total-ms 600000}))           ; 10 minutes

  ;; Record streaming chunks
  (record-chunk! monitor "Analyzing the requirements...")
  (record-chunk! monitor "Creating plan...")
  (record-chunk! monitor "thinking")  ; Won't count as progress
  (record-chunk! monitor "thinking")  ; Stagnation detected

  ;; Record file writes
  (record-file-write! monitor "src/foo.clj")

  ;; Check for timeout
  (check-timeout monitor)
  ;; => nil (still making progress)
  ;; or => {:type :stagnation :elapsed-ms 125000 ...}

  ;; Get stats
  (get-stats monitor))
