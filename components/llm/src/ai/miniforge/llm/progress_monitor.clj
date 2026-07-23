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
   changes to detect when an agent is stuck vs actively working.")

;------------------------------------------------------------------------------ Layer 0
;; Timeout thresholds and monitor state

(def ^:private default-stagnation-threshold-ms
  "Time without any progress signal before an agent is considered stuck —
   the adaptive-timeout trip point. 2 minutes: long enough to ride out a slow
   model turn or a quiet tool call, short enough that a genuinely hung agent
   is caught before it burns a phase budget."
  120000)

(def ^:private default-max-total-ms
  "Hard ceiling on a single monitored run regardless of progress. 10 minutes —
   a backstop for an agent that keeps emitting just enough to look active but
   never actually finishes."
  600000)

(def ^:private default-min-activity-interval-ms
  "Minimum spacing between counted progress signals; debounces a burst of
   chunks into one activity tick so rapid streaming doesn't reset the
   stagnation clock on every token. 5 seconds."
  5000)

(def ^:private min-substantive-chunk-length
  "A streamed chunk with this length or fewer characters is treated as
   non-substantive — a spinner/keepalive blip, not real output — so it does
   not count as progress."
  10)

(def ^:private active-window-ms
  "Recency window for the :is-active? status flag: activity newer than this
   marks the monitor active. 30 seconds."
  30000)

(defn create-progress-monitor
   "Create a progress monitor for adaptive timeout.

   Options:
   - :stagnation-threshold-ms - Time without progress before considering stuck (default: default-stagnation-threshold-ms)
   - :max-total-ms - Hard limit regardless of progress (default: default-max-total-ms)
   - :min-activity-interval-ms - Minimum time between progress signals (default: default-min-activity-interval-ms)

   Returns monitor state atom."
   [{:keys [stagnation-threshold-ms max-total-ms min-activity-interval-ms]
     :or {stagnation-threshold-ms default-stagnation-threshold-ms
          max-total-ms default-max-total-ms
          min-activity-interval-ms default-min-activity-interval-ms}}]
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
         is-substantive? (and chunk-content (> (count chunk-content) min-substantive-chunk-length))
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

(defn record-activity!
  "Record semantic activity that should keep the monitor alive even when
   repeated events do not produce new substantive text.

   Arguments:
   - monitor - Progress monitor atom
   - activity-key - Stable keyword/string describing the activity type"
  [monitor activity-key]
  (let [now (System/currentTimeMillis)]
    (swap! monitor
           (fn [state]
             (-> state
                 (assoc :last-activity-at now
                        :stagnant-cycles 0)
                 (update :chunk-count inc)
                 (update :unique-chunks conj activity-key)))))
  true)

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

;------------------------------------------------------------------------------ Layer 1
;; Keepalive — decouples stagnation from CLI emission cadence

(def ^:private keepalive-stop-join-ms
  "How long stop! waits for the keepalive thread to exit after
   interrupting it. 100ms is generous — the worker only needs to
   wake from Thread/sleep, see the running? flag, and exit."
  100)

(defn start-keepalive!
  "Spawn a daemon thread that calls record-activity! on `monitor` every
   `interval-ms` until the returned 0-arity stop-fn is invoked.

   ## Why

   Stage-3 dogfood (2026-05-07) failed at the planner with
   'Stagnation timeout: no progress for 180047ms'. Diagnosis: the
   claude CLI in `--input-format text` mode does not emit
   `rate_limit_event` during the model's think phase. Without
   those heartbeats our stream-heartbeat-based stagnation guard has
   no signal during legitimate model thinking on heavy prompts.

   Keepalive decouples the per-run liveness check from the CLI's
   own emission cadence — as long as the LLM-client's reader thread
   is alive (i.e., this JVM is alive and the subprocess hasn't
   wedged the JVM), the monitor's stagnation timer keeps refreshing.
   `max-total-ms` remains the OS-wedge backstop.

   This is the pragmatic stopgap until the Stage 3 progress-detector
   wiring (semantic loop detection) replaces the wallclock approach
   wholesale.

   ## Arguments
     monitor     - a progress monitor atom (from create-progress-monitor)
     interval-ms - positive integer; how often to refresh. Must be
                   strictly less than stagnation-threshold-ms or the
                   keepalive will fire too late to prevent stagnation.

   ## Returns
     0-arity stop-fn. Calling it interrupts the worker thread and
     waits up to `keepalive-stop-join-ms` for it to exit, so callers
     can rely on no further `record-activity!` calls landing once
     stop! has returned (modulo a single in-flight tick that already
     started before the interrupt — kept harmless by the running?
     check guarding record-activity!)."
  [monitor interval-ms]
  (assert (and (integer? interval-ms) (pos? interval-ms))
          "keepalive interval-ms must be a positive integer")
  (let [running? (atom true)
        thread   (Thread.
                   (fn []
                     (try
                       (while @running?
                         (Thread/sleep ^long interval-ms)
                         (when @running?
                           (record-activity! monitor :keepalive)))
                       (catch InterruptedException _
                         ;; stop! interrupted us mid-sleep; exit cleanly.
                         nil)))
                   "llm-progress-monitor-keepalive")]
    (.setDaemon thread true)
    (.start thread)
    (fn stop! []
      (reset! running? false)
      (.interrupt thread)
      (.join thread keepalive-stop-join-ms))))

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
     :is-active? (< (- now last-activity-at) active-window-ms)}))

(comment
  ;; Usage example
  (def monitor (create-progress-monitor
                {:stagnation-threshold-ms default-stagnation-threshold-ms
                 :max-total-ms default-max-total-ms}))

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
