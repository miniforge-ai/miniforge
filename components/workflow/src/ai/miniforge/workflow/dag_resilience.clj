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

(ns ai.miniforge.workflow.dag-resilience
  "Rate limit detection, backend failover, and DAG pause/resume support.

   Keeps the DAG orchestrator under 400 lines by extracting resilience
   concerns into this module. Three layers:
   - Layer 0: Rate limit detection from error patterns
   - Layer 1: Backend failover + event emission
   - Layer 2: Batch analysis + rate limit handling
   - Layer 3: Resume — reconstruct completed-ids from event file"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;--- Layer 0: Rate Limit Detection

(defn load-rate-limit-patterns
  "Load and compile rate-limit patterns from external error patterns.
   Returns a seq of compiled regex patterns."
  []
  (try
    (let [load-fn (requiring-resolve 'ai.miniforge.agent-runtime.error-classifier.patterns/external-patterns)]
      (->> @load-fn
           (filter :rate-limit?)
           (map :regex)))
    (catch Exception _
      ;; Fallback patterns if error-classifier not available
      (map re-pattern
           ["(?i)you've hit your limit"
            "(?i)rate.?limit"
            "(?i)quota.?exceeded"
            "429|Too Many Requests"
            "resets \\d+[ap]m|resets in \\d+"]))))

(def rate-limit-patterns
  (delay (load-rate-limit-patterns)))

(defn rate-limit-in-text?
  "Check if arbitrary text contains rate limit patterns.
   Useful for scanning agent output, error messages, or phase results."
  [text]
  (when (and (string? text) (seq text))
    (boolean (some #(re-find % text) @rate-limit-patterns))))

(def ^:private reset-time-pattern
  "Matches 'resets 2pm', 'resets 2:30pm', 'resets 2pm (America/Los_Angeles)'"
  #"resets\s+(\d{1,2}(?::\d{2})?\s*[ap]m)(?:\s*\(([^)]+)\))?")

(def ^:private reset-duration-pattern
  "Matches 'resets in 30 minutes', 'resets in 2 hours'"
  #"resets\s+in\s+(\d+)\s*(minutes?|hours?|seconds?)")

(defn parse-reset-instant
  "Parse rate limit reset time from message text.
   Returns java.time.Instant or nil.

   Handles:
   - 'resets 2pm' — assumes local timezone
   - 'resets 2pm (America/Los_Angeles)' — uses specified timezone
   - 'resets in 30 minutes' — relative duration"
  [text]
  (when (string? text)
    (or
     ;; Absolute time: "resets 2pm (America/Los_Angeles)"
     (when-let [m (re-find reset-time-pattern text)]
       (try
         (let [time-str (nth m 1)
               tz-str (nth m 2)
               zone (if tz-str
                      (java.time.ZoneId/of tz-str)
                      (java.time.ZoneId/systemDefault))
               ;; Parse "2pm" or "2:30pm" — case-insensitive for am/pm
               pattern (if (re-find #":" time-str) "h:mma" "ha")
               formatter (-> (java.time.format.DateTimeFormatterBuilder.)
                             (.parseCaseInsensitive)
                             (.appendPattern pattern)
                             (.toFormatter))
               local-time (java.time.LocalTime/parse
                           (.trim time-str)
                           formatter)
               now (java.time.ZonedDateTime/now zone)
               reset-today (.with now local-time)
               ;; If reset time is in the past, it means tomorrow
               reset (if (.isBefore reset-today now)
                       (.plusDays reset-today 1)
                       reset-today)]
           (.toInstant reset))
         (catch Exception _ nil)))

     ;; Relative time: "resets in 30 minutes"
     (when-let [m (re-find reset-duration-pattern text)]
       (try
         (let [amount (Long/parseLong (nth m 1))
               unit (nth m 2)
               duration (cond
                          (re-find #"second" unit) (java.time.Duration/ofSeconds amount)
                          (re-find #"minute" unit) (java.time.Duration/ofMinutes amount)
                          (re-find #"hour" unit) (java.time.Duration/ofHours amount))]
           (.plus (java.time.Instant/now) duration))
         (catch Exception _ nil))))))

(def ^:private short-wait-threshold-ms
  "Maximum time to wait in-process for a rate limit reset (30 minutes).
   Below this: sleep and auto-resume. Above this: checkpoint and wind down."
  (* 30 60 1000))

(def ^:private medium-wait-threshold-ms
  "Maximum time for a scheduled resume (2 hours).
   Between short and medium: checkpoint, wind down, schedule auto-resume.
   Above this: checkpoint, wind down, require manual resume."
  (* 2 60 60 1000))

(defn millis-until-reset
  "Calculate milliseconds until a reset instant. Returns 0 if already past."
  [reset-instant]
  (max 0 (- (.toEpochMilli reset-instant) (System/currentTimeMillis))))

(defn rate-limit-error?
  "Check if a DAG result indicates a rate limit error."
  [result]
  (when-not (dag/ok? result)
    (let [raw (or (get-in result [:error :message])
                  (get-in result [:error :data :message])
                  (str result))
          msg (if (string? raw) raw (str raw))]
      (rate-limit-in-text? msg))))

(defn find-healthy-backend
  "Find a healthy backend from the allowed list, excluding the current one.
   Records the current backend's failure, then returns the first candidate."
  [current-backend allowed-backends]
  (try
    (let [record-call! (requiring-resolve 'ai.miniforge.self-healing.backend-health/record-backend-call!)]
      (record-call! current-backend false)
      (first (filter #(not= % (keyword current-backend))
                     (map keyword allowed-backends))))
    (catch Exception _
      ;; If backend-health isn't available, still pick from allowed list
      (first (filter #(not= % (keyword current-backend))
                     (map keyword allowed-backends))))))

;--- Layer 1: Backend Failover + Event Emission

(defn attempt-backend-switch
  "Attempt to switch to an alternative backend after rate limiting.

   Returns {:switched? true :new-backend :openai} or
           {:switched? false :reason ...}"
  [current-backend allowed-backends cost-ceiling accumulated-cost logger]
  (cond
    ;; Cost ceiling exceeded
    (and cost-ceiling (> accumulated-cost cost-ceiling))
    (do (log/info logger :dag-resilience :failover/cost-ceiling-exceeded
                  {:data {:ceiling cost-ceiling :accumulated accumulated-cost}})
        {:switched? false :reason :cost-ceiling})

    ;; No allowed backends configured (user didn't opt in)
    (empty? allowed-backends)
    (do (log/info logger :dag-resilience :failover/no-allowed-backends {})
        {:switched? false :reason :no-allowed-backends})

    ;; Try to find a healthy backend from the allowed list
    :else
    (let [candidate (find-healthy-backend current-backend allowed-backends)]
      (if candidate
        (try
          (let [trigger-switch! (requiring-resolve 'ai.miniforge.self-healing.backend-health/trigger-backend-switch!)
                result (trigger-switch! current-backend candidate)]
            (log/info logger :dag-resilience :failover/switched
                      {:data {:from current-backend :to candidate}})
            {:switched? true :new-backend candidate :switch-result result})
          (catch Exception e
            (log/info logger :dag-resilience :failover/error
                      {:data {:error (.getMessage e)}})
            {:switched? false :reason :failover-error :error (.getMessage e)}))
        (do (log/info logger :dag-resilience :failover/exhausted
                      {:data {:allowed allowed-backends}})
            {:switched? false :exhausted? true :reason :all-allowed-exhausted})))))

(defn emit-dag-task-completed!
  "Publish :dag/task-completed event for checkpointing."
  [event-stream workflow-id task-id result]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/task-completed
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/task-id task-id
                   :dag/result (select-keys result [:data :status])}))
      (catch Exception _ nil))))

(defn emit-dag-paused!
  "Publish :dag/paused event with completed task IDs for resume."
  [event-stream workflow-id completed-ids reason]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/paused
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/completed-task-ids (vec completed-ids)
                   :dag/pause-reason reason}))
      (catch Exception _ nil))))

;--- Layer 2: Batch Analysis + Rate Limit Handling

(defn analyze-batch-for-rate-limits
  "Categorize batch results into completed, rate-limited, and other-failed."
  [results]
  (reduce (fn [acc [task-id result]]
            (cond
              (dag/ok? result)
              (update acc :completed-ids conj task-id)

              (rate-limit-error? result)
              (update acc :rate-limited-ids conj task-id)

              :else
              (update acc :other-failed-ids conj task-id)))
          {:completed-ids #{} :rate-limited-ids #{} :other-failed-ids #{}}
          results))

(defn extract-rate-limit-messages
  "Extract error messages from rate-limited DAG results."
  [results rate-limited-ids]
  (->> rate-limited-ids
       (map #(get results %))
       (keep #(or (get-in % [:error :message])
                  (get-in % [:error :data :message])))
       (filter string?)))

(defn find-reset-instant
  "Scan rate limit error messages for a reset time. Returns Instant or nil."
  [messages]
  (some parse-reset-instant messages))

(defn wait-for-reset!
  "Decide how to handle a rate limit reset based on wait duration.

   Returns one of:
   - {:waited? true :wait-ms N}           — slept in-process, ready to continue
   - {:waited? false :tier :medium ...}   — too long to sleep, checkpoint and schedule resume
   - {:waited? false :tier :long ...}     — way too long, checkpoint and require manual resume"
  [reset-instant logger]
  (let [wait-ms (millis-until-reset reset-instant)
        wait-minutes (/ wait-ms 60000.0)]
    (cond
      ;; Already past — continue immediately
      (<= wait-ms 0)
      {:waited? true :wait-ms 0}

      ;; Short wait (< 30min) — sleep in-process
      (<= wait-ms short-wait-threshold-ms)
      (do (log/info logger :dag-resilience :rate-limit/waiting-short
                    {:data {:reset-at (str reset-instant)
                            :wait-ms wait-ms
                            :wait-minutes wait-minutes}})
          (Thread/sleep wait-ms)
          {:waited? true :wait-ms wait-ms})

      ;; Medium wait (30min - 2hrs) — checkpoint, schedule auto-resume
      (<= wait-ms medium-wait-threshold-ms)
      (do (log/info logger :dag-resilience :rate-limit/checkpoint-medium
                    {:data {:reset-at (str reset-instant)
                            :wait-ms wait-ms
                            :wait-minutes wait-minutes
                            :action "checkpoint-and-schedule-resume"}})
          {:waited? false
           :tier :medium
           :reset-at reset-instant
           :wait-ms wait-ms
           :reason (format "Rate limit resets in %.0f minutes — checkpointing and scheduling resume"
                           wait-minutes)})

      ;; Long wait (> 2hrs) — checkpoint, require manual resume
      :else
      (do (log/info logger :dag-resilience :rate-limit/checkpoint-long
                    {:data {:reset-at (str reset-instant)
                            :wait-ms wait-ms
                            :wait-minutes wait-minutes
                            :action "checkpoint-and-wind-down"}})
          {:waited? false
           :tier :long
           :reset-at reset-instant
           :wait-ms wait-ms
           :reason (format "Rate limit resets in %.0f minutes — checkpointing for manual resume"
                           wait-minutes)}))))

(defn- try-wait-for-reset
  "Strategy 1: Wait for a known reset time.

   Short waits (< 30min): sleep in-process, return {:action :continue}.
   Medium waits (30min-2hrs): return {:action :checkpoint-and-resume} with reset time.
   Long waits (> 2hrs): return {:action :checkpoint-and-stop} with reset time.
   Unknown reset time: return nil to fall through to next strategy."
  [results rate-limited-ids logger]
  (when results
    (let [msgs (extract-rate-limit-messages results rate-limited-ids)
          reset-instant (find-reset-instant msgs)]
      (when reset-instant
        (let [{:keys [waited? tier] :as wait-result} (wait-for-reset! reset-instant logger)]
          (cond
            ;; Short wait succeeded — continue immediately
            waited?
            {:action :continue :waited-ms (:wait-ms wait-result)}

            ;; Medium wait — checkpoint and schedule auto-resume
            (= :medium tier)
            {:action :checkpoint-and-resume
             :reset-at (:reset-at wait-result)
             :wait-ms (:wait-ms wait-result)
             :reason (:reason wait-result)}

            ;; Long wait — checkpoint and stop
            (= :long tier)
            {:action :checkpoint-and-stop
             :reset-at (:reset-at wait-result)
             :wait-ms (:wait-ms wait-result)
             :reason (:reason wait-result)}))))))

(defn- try-backend-failover
  "Strategy 2: Switch to an alternative backend.
   Returns {:action :continue ...} on success, nil to fall through."
  [context logger]
  (let [self-healing (or (get-in context [:execution/opts :self-healing])
                         (get-in context [:user-config :self-healing])
                         {})
        auto-switch? (get self-healing :backend-auto-switch true)
        allowed (get self-healing :allowed-failover-backends [])
        cost-ceiling (get self-healing :max-cost-per-workflow)
        accumulated-cost (get-in context [:execution/metrics :cost-usd] 0.0)
        current-backend (or (get-in context [:llm-backend :backend-type])
                            (get context :current-backend :claude))]
    (when (and auto-switch? (seq allowed))
      (let [switch-result (attempt-backend-switch
                           current-backend allowed cost-ceiling accumulated-cost logger)]
        (when (:switched? switch-result)
          {:action :continue :new-backend (:new-backend switch-result)})))))

(defn- pause-with-reason
  "Strategy 3 (terminal): Pause with an explanatory reason."
  [context]
  (let [self-healing (or (get-in context [:execution/opts :self-healing])
                         (get-in context [:user-config :self-healing])
                         {})
        auto-switch? (get self-healing :backend-auto-switch true)]
    {:action :pause
     :reason (cond
               (not auto-switch?)
               "Rate limited. Backend auto-switch is disabled."

               (empty? (get self-healing :allowed-failover-backends []))
               "Rate limited. No failover backends configured (set :allowed-failover-backends)."

               :else
               "Rate limited. All recovery strategies exhausted.")}))

(defn handle-rate-limited-batch
  "Orchestrate the failover decision for a rate-limited batch.

   Runs a tiered strategy chain:
   1. Short wait (< 30min): sleep in-process, auto-continue
   2. Backend failover: switch to alternative LLM backend
   3. Medium wait (30min-2hrs): checkpoint, schedule auto-resume
   4. Long wait (> 2hrs): checkpoint, wind down for manual resume
   5. Unknown reset time: pause with reason

   Returns:
   - {:action :continue ...}              — ready to retry immediately
   - {:action :checkpoint-and-resume ...}  — wind down, resume later
   - {:action :checkpoint-and-stop ...}    — wind down, manual resume needed
   - {:action :pause :reason \"...\"}       — stop, no resume info"
  ([context rate-limited-ids completed-ids logger]
   (handle-rate-limited-batch context rate-limited-ids completed-ids logger nil))
  ([context rate-limited-ids completed-ids logger results]
   (log/info logger :dag-resilience :rate-limit/detected
             {:data {:rate-limited-count (count rate-limited-ids)
                     :completed-count (count completed-ids)}})
   (let [wait-decision (try-wait-for-reset results rate-limited-ids logger)]
     (cond
       ;; Short wait succeeded or medium/long tier returned
       (= :continue (:action wait-decision))
       wait-decision

       ;; Medium/long wait — try backend failover first, fall back to checkpoint
       (#{:checkpoint-and-resume :checkpoint-and-stop} (:action wait-decision))
       (or (try-backend-failover context logger)
           wait-decision)

       ;; No reset time found — try failover, then generic pause
       :else
       (or (try-backend-failover context logger)
           (pause-with-reason context))))))

;--- Layer 3: Resume — Reconstruct from Event File

(defn safe-read-edn
  "Read an EDN string, returning nil on parse failure.
   Handles #object, #uuid, and #inst tagged literals gracefully."
  [s]
  (try
    (let [readers (merge {'object (fn [& args] (str "#object" args))
                          'uuid   (fn [s] (java.util.UUID/fromString s))
                          'inst   (fn [s] (java.time.Instant/parse s))}
                         clojure.core/*data-readers*)]
      (edn/read-string {:readers readers :default tagged-literal} s))
    (catch Exception _ nil)))

(defn read-event-file
  "Read all events from a workflow event file.
   Returns a vector of parsed event maps, skipping unparseable lines."
  [file-path]
  (let [f (io/file file-path)]
    (when (.exists f)
      (with-open [rdr (io/reader f)]
        (->> (line-seq rdr)
             (keep (fn [line]
                     (when-not (str/blank? line)
                       (safe-read-edn line))))
             vec)))))

(defn extract-completed-task-ids
  "Extract the set of completed DAG task IDs from a sequence of events.
   Looks for :dag/task-completed events and returns their task IDs."
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (map :dag/task-id)
       set))

(defn extract-completed-artifacts
  "Extract artifacts from completed task events for merging into results."
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (mapcat #(get-in % [:dag/result :data :artifacts] []))
       vec))

(def default-events-dir
  "Default directory for workflow event files."
  (delay (str (System/getProperty "user.home") "/.miniforge/events")))

(defn resume-context-from-event-file
  "Build resume context from a workflow's event file.

   Arguments:
   - workflow-id: UUID of the workflow to resume
   - opts: Optional map with :events-dir to override default path

   Returns a map suitable for merging into the DAG execution context:
   {:pre-completed-ids #{task-id-1 task-id-2 ...}
    :pre-completed-artifacts [artifact-1 ...]
    :resumed? true}"
  ([workflow-id] (resume-context-from-event-file workflow-id {}))
  ([workflow-id opts]
   (let [events-dir (or (:events-dir opts) @default-events-dir)
         file-path (str events-dir "/" workflow-id ".edn")
         events (read-event-file file-path)]
     (if (seq events)
       (let [completed-ids (extract-completed-task-ids events)
             artifacts (extract-completed-artifacts events)]
         {:pre-completed-ids completed-ids
          :pre-completed-artifacts artifacts
          :resumed? true
          :resume-source file-path})
       {:pre-completed-ids #{}
        :pre-completed-artifacts []
        :resumed? false}))))
