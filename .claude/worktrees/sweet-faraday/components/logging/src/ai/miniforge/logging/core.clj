(ns ai.miniforge.logging.core
  "Structured EDN logging implementation.
   Layer 0: Pure functions for log entry creation
   Layer 1: Logger protocol and default implementation"
  (:require
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions for log entry creation

(defn make-entry
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

(defn merge-context
  "Merge context map into a log entry, preserving entry values on conflict."
  [entry context]
  (merge context entry))

(defn level-enabled?
  "Check if a log level should be emitted given the configured minimum level."
  [configured-level entry-level]
  (let [level-order {:trace 0 :debug 1 :info 2 :warn 3 :error 4 :fatal 5}
        configured-ord (get level-order configured-level 0)
        entry-ord (get level-order entry-level 0)]
    (>= entry-ord configured-ord)))

(defn format-edn
  "Format a log entry as an EDN string for output."
  [entry]
  (pr-str entry))

(defn format-human
  "Format a log entry as a human-readable string."
  [entry]
  (let [{:log/keys [timestamp level category event message]} entry]
    (str (when timestamp (.toInstant timestamp))
         " [" (name level) "] "
         (name category) "/" (name event)
         (when message (str " - " message)))))

;------------------------------------------------------------------------------ Layer 1
;; Logger protocol and implementations

(defprotocol Logger
  "Protocol for structured logging."
  (log* [this level category event opts]
    "Emit a structured log entry. opts may include :message, :data, and context keys.")
  (with-context* [this context-map]
    "Return a new logger with additional context merged into all entries.")
  (get-context [this]
    "Return the current context map.")
  (get-config [this]
    "Return the logger configuration."))

(defrecord EDNLogger [config context output-fn]
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

(defn default-output-fn
  "Default output function that prints EDN to *out*."
  [entry]
  (println (format-edn entry)))

(defn human-output-fn
  "Human-readable output function."
  [entry]
  (println (format-human entry)))

(defn collecting-output-fn
  "Returns [output-fn, entries-atom] for collecting entries in tests."
  []
  (let [entries (atom [])]
    [(fn [entry] (swap! entries conj entry))
     entries]))

(defn log-file-path
  "Get path to log file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to ~/.miniforge/logs/<workflow-id>.log"
  [workflow-id]
  (let [home (System/getProperty "user.home")
        logs-dir (io/file home ".miniforge" "logs")
        log-file (str workflow-id ".log")]
    (.mkdirs logs-dir)
    (.getPath (io/file logs-dir log-file))))

(defn file-size-mb
  "Get file size in megabytes.

   Arguments:
     file-path - String path to file

   Returns: Double size in MB"
  [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (/ (.length file) 1024.0 1024.0)
      0.0)))

(defn rotate-log-if-needed
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

(defn file-output-fn
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

(defn combined-output-fn
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

(defn create-logger
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
                     (let [multi-sink (requiring-resolve 'ai.miniforge.logging.sinks/multi-sink)]
                       (multi-sink sinks))

                     ;; Create sinks from config
                     config
                     (let [create-from-config (requiring-resolve 'ai.miniforge.logging.sinks/create-sinks-from-config)]
                       (create-from-config config))

                     ;; Legacy output mode
                     :else
                     (case output
                       :edn default-output-fn
                       :human human-output-fn
                       output))]
     (->EDNLogger {:min-level min-level} (or context {}) output-fn))))

(defn timed*
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
