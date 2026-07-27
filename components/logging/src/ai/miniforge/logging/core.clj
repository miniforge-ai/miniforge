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
(ns ai.miniforge.logging.core
  "Structured EDN logging implementation.
   Layer 0: Pure functions for log entry creation
   Layer 1: Logger protocol and default implementation"
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.logging.format :as log-format]
   [ai.miniforge.logging.sinks :as sinks]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

;; Pure functions for log entry creation
(defn ^{:stratum 0} make-entry
  "Create a log entry map with required fields.
   Additional context can be merged in."
  [level category event & {:keys [message data]}]
  (let [base {:log/id (random-uuid)
              :log/timestamp (java.util.Date.)
              :log/level level
              :log/category category
              :log/event event}]
    (cond-> base
      message (assoc :log/message message)
      data (assoc :data data))))

(defn ^{:stratum 0} merge-context
  "Merge context map into a log entry, preserving entry values on conflict."
  [entry context]
  (merge context entry))

(defn ^{:stratum 0} level-enabled?
  "Check if a log level should be emitted given the configured minimum level."
  [configured-level entry-level]
  (log-format/level-enabled? configured-level entry-level))

(defn ^{:stratum 0} format-edn
  "Format a log entry as an EDN string for output."
  [entry]
  (log-format/format-edn entry))

(defn ^{:stratum 0} format-human
  "Format a log entry as a human-readable string."
  [entry]
  (log-format/format-human entry))

;; Logger protocol and implementations
(defprotocol ^{:stratum 0} Logger
  "Protocol for structured logging."
  (log* [this level category event opts]
    "Emit a structured log entry. opts may include :message, :data, and context keys.")
  (with-context* [this context-map]
    "Return a new logger with additional context merged into all entries.")
  (get-context [this]
    "Return the current context map.")
  (get-config [this]
    "Return the logger configuration."))

(defn ^{:stratum 0} collecting-output-fn
  "Returns [output-fn, entries-atom] for collecting entries in tests."
  []
  (let [entries (atom [])]
    [(fn [entry] (swap! entries conj entry))
     entries]))

(defn ^{:stratum 0} log-file-path
  "Get path to log file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to ~/.miniforge/logs/<workflow-id>.log"
  [workflow-id]
  (let [logs-dir (io/file (config/miniforge-home) "logs")
        log-file (str workflow-id ".log")]
    (.mkdirs logs-dir)
    (.getPath (io/file logs-dir log-file))))

(defn ^{:stratum 0} file-size-mb
  "Get file size in megabytes.

   Arguments:
     file-path - String path to file

   Returns: Double size in MB"
  [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (/ (.length file) 1024.0 1024.0)
      0.0)))

(defn ^{:stratum 0} cleanup-old-rotated-logs
  "Delete rotated log files in `logs-dir` older than `retention-days`.

   Rotated files are named like `<workflow-id>.log.yyyyMMdd-HHmmss`.
   Returns the number of files deleted."
  [logs-dir retention-days]
  (let [dir (io/file logs-dir)
        retention-days (max 0 (long retention-days))
        cutoff (- (System/currentTimeMillis)
                  (* retention-days 24 60 60 1000))
        rotated-log? #(boolean (re-matches #".+\.log\.\d{8}-\d{6}" (.getName ^java.io.File %)))]
    (if-not (.exists dir)
      0
      (->> (.listFiles dir)
           (filter rotated-log?)
           (filter #(< (.lastModified ^java.io.File %) cutoff))
           (filter #(.delete ^java.io.File %))
           count))))

(defn ^{:stratum 0} combined-output-fn
  "Combine multiple output functions.

   Arguments:
     output-fns - Vector of output functions

   Returns: Combined output function"
  [output-fns]
  (fn [entry]
    (doseq [output-fn output-fns]
      (try
        (output-fn entry)
        (catch Exception _e
          ;; Continue with other outputs even if one fails
          nil)))))

(defn ^{:stratum 0} timed*
  "Execute f, logging start and completion with duration.
   Returns [result duration-ms]."
  [logger level category event f]
  (log* logger level category event {:message "started"})
  (let [start (System/currentTimeMillis)
        result (f)
        duration (- (System/currentTimeMillis) start)]
    (log* logger level category event
          {:message "completed"
           :data {:duration-ms duration}})
    [result duration]))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} EDNLogger [config context output-fn]
  Logger
  (log* [_this level category event opts]
    (when (level-enabled? (:min-level config :trace) level)
      (let [entry (-> (apply make-entry level category event (mapcat identity opts))
                      (merge-context context))]
        (output-fn entry)
        entry)))

  (with-context* [_this context-map]
    (->EDNLogger config (merge context context-map) output-fn))

  (get-context [_this]
    context)

  (get-config [_this]
    config))

(defn ^{:stratum 1} default-output-fn
  "Default output function that prints EDN to *out*."
  [entry]
  (println (format-edn entry)))

(defn ^{:stratum 1} human-output-fn
  "Human-readable output function."
  [entry]
  (println (format-human entry)))

(defn ^{:stratum 1} rotate-log-if-needed
  "Rotate log file if it exceeds size threshold.

   Arguments:
     file-path - String path to log file
     max-size-mb - Maximum size in MB (default 10MB)

   Returns: nil"
  [file-path max-size-mb]
  (when (> (file-size-mb file-path) max-size-mb)
    (let [timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
          rotated-path (str file-path "." timestamp)]
      (.renameTo (io/file file-path) (io/file rotated-path)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} file-output-fn
  "Output function that writes logs to per-workflow files.

   DEPRECATED: Use ai.miniforge.logging.sinks/file-sink instead.
   Kept for backward compatibility.

   Logs are written to ~/.miniforge/logs/<workflow-id>.log
   Files are automatically rotated when they exceed 10MB.

   Arguments:
     opts - Map with optional:
       :max-size-mb - Max file size before rotation (default 10)
       :format - :edn or :human (default :human)

   Returns: Output function"
  [& [opts]]
  (let [max-size-mb (:max-size-mb opts 10)
        format-fn (case (:format opts :human)
                    :edn format-edn
                    :human format-human
                    format-human)]
    (fn [entry]
      (when-let [workflow-id (:workflow/id entry)]
        (try
          (let [file-path (log-file-path workflow-id)]
            (rotate-log-if-needed file-path max-size-mb)
            (with-open [writer (io/writer file-path :append true)]
              (.write writer (format-fn entry))
              (.write writer "\n")))
          (catch Exception _e
            ;; Silently fail - don't break logging if file write fails
            nil))))))

(defn ^{:stratum 2} create-logger
  "Create a new EDN logger with the given configuration.

   Options:
   - :min-level - Minimum log level to emit (default :trace)
   - :output    - Output mode :edn (default), :human, or custom function
   - :context   - Initial context map
   - :config    - User config map to create sinks from
   - :sinks     - Explicit sink functions (overrides :output)

   When :config is provided, uses ai.miniforge.logging.sinks to create
   configurable sinks from user configuration.

   Example:
     ;; Simple stdout logger
     (create-logger {:output :human})

     ;; Logger with configurable sinks
     (create-logger {:config user-config})

     ;; Logger with explicit sinks
     (create-logger {:sinks [(sinks/file-sink) (sinks/stdout-sink)]})"
  ([] (create-logger {}))
  ([{:keys [min-level output context config sinks] :or {min-level :trace output :edn}}]
   (let [output-fn (cond
                     ;; Explicit sinks provided
                     sinks
                     (sinks/multi-sink sinks)

                     ;; Create sinks from config
                     config
                     (sinks/create-sinks-from-config config)

                     ;; Legacy output mode
                     :else
                     (case output
                       :edn default-output-fn
                       :human human-output-fn
                       output))]
     (->EDNLogger {:min-level min-level} (or context {}) output-fn))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a logger
  (def logger (create-logger {:min-level :debug :output :human}))

  ;; Log an event
  (log* logger :info :agent :agent/task-started
        {:message "Starting task"
         :data {:agent-role :implementer}})

  ;; Add context
  (def ctx-logger (with-context* logger {:ctx/workflow-id (random-uuid)}))
  (log* ctx-logger :debug :loop :inner/iteration-started
        {:data {:iteration 1}})

  ;; Timed execution
  (timed* logger :info :system :system/health-check
          #(do (Thread/sleep 100) :healthy))

  ;; Collecting logger for tests
  (let [[output-fn entries] (collecting-output-fn)
        test-logger (->EDNLogger {:min-level :trace} {} output-fn)]
    (log* test-logger :info :agent :agent/task-started {})
    @entries)

  :leave-this-here)
