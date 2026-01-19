(ns ai.miniforge.logging.interface
  "Public API for structured EDN logging.
   Provides logger creation, context management, and level-specific log functions."
  (:require
   [ai.miniforge.logging.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Logger creation and configuration

(defn create-logger
  "Create a new EDN logger.

   Options:
   - :min-level - Minimum log level to emit (:trace :debug :info :warn :error :fatal)
   - :output    - Output mode :edn (default), :human, or custom (fn [entry] ...)
   - :context   - Initial context map to include in all log entries

   Example:
     (create-logger {:min-level :info :output :human})"
  ([] (core/create-logger))
  ([opts] (core/create-logger opts)))

(defn with-context
  "Return a new logger with additional context merged into all entries.
   Context keys are typically namespaced like :ctx/workflow-id, :ctx/agent-id.

   Example:
     (with-context logger {:ctx/workflow-id (random-uuid)
                           :ctx/task-id task-id})"
  [logger context-map]
  (core/with-context* logger context-map))

(defn get-context
  "Return the current context map from a logger."
  [logger]
  (core/get-context logger))

(defn collecting-logger
  "Create a logger that collects entries into an atom for testing.
   Returns [logger entries-atom].

   Example:
     (let [[logger entries] (collecting-logger)]
       (info logger :agent :agent/task-started {})
       @entries)"
  ([] (collecting-logger {}))
  ([opts]
   (let [[output-fn entries] (core/collecting-output-fn)]
     [(core/create-logger (assoc opts :output output-fn))
      entries])))

;------------------------------------------------------------------------------ Layer 1
;; Core logging function

(defn log
  "Emit a structured log entry.

   Arguments:
   - logger   - Logger instance
   - level    - Log level (:trace :debug :info :warn :error :fatal)
   - category - Event category (:agent :loop :policy :artifact :system)
   - event    - Specific event keyword (e.g. :agent/task-started)
   - opts     - Map with optional :message (string) and :data (map)

   Example:
     (log logger :info :agent :agent/task-started
          {:message \"Starting implementation\"
           :data {:agent-role :implementer}})"
  [logger level category event opts]
  (core/log* logger level category event opts))

;; Level-specific convenience functions

(defn trace
  "Log at :trace level (detailed internal state)."
  ([logger category event] (trace logger category event {}))
  ([logger category event opts]
   (core/log* logger :trace category event opts)))

(defn debug
  "Log at :debug level (operational detail)."
  ([logger category event] (debug logger category event {}))
  ([logger category event opts]
   (core/log* logger :debug category event opts)))

(defn info
  "Log at :info level (business events)."
  ([logger category event] (info logger category event {}))
  ([logger category event opts]
   (core/log* logger :info category event opts)))

(defn warn
  "Log at :warn level (recoverable issues)."
  ([logger category event] (warn logger category event {}))
  ([logger category event opts]
   (core/log* logger :warn category event opts)))

(defn error
  "Log at :error level (failed operations)."
  ([logger category event] (error logger category event {}))
  ([logger category event opts]
   (core/log* logger :error category event opts)))

(defn fatal
  "Log at :fatal level (system-level failures)."
  ([logger category event] (fatal logger category event {}))
  ([logger category event opts]
   (core/log* logger :fatal category event opts)))

;------------------------------------------------------------------------------ Layer 2
;; Timed execution

(defn timed
  "Execute f and log start/completion with duration.
   Returns the result of f.

   Example:
     (timed logger :info :system :system/health-check
            #(check-health))"
  [logger level category event f]
  (first (core/timed* logger level category event f)))

(defmacro with-timing
  "Execute body and log start/completion with duration.
   Returns the result of body.

   Example:
     (with-timing logger :info :agent :agent/task-completed
       (do-expensive-work))"
  [logger level category event & body]
  `(timed ~logger ~level ~category ~event (fn [] ~@body)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a logger with human-readable output
  (def logger (create-logger {:min-level :debug :output :human}))

  ;; Log at different levels
  (info logger :agent :agent/task-started {:message "Starting task"})
  (debug logger :loop :inner/iteration-started {:data {:iteration 1}})
  (warn logger :policy :policy/budget-exceeded {:data {:used 100 :limit 50}})

  ;; Add context for a workflow
  (def workflow-logger
    (with-context logger
      {:ctx/workflow-id (random-uuid)
       :ctx/phase :implement}))

  (info workflow-logger :agent :agent/task-completed
        {:data {:tokens-used 1500}})

  ;; Timed execution
  (timed logger :info :system :system/health-check
         #(do (Thread/sleep 50) :ok))

  ;; Collecting logger for tests
  (let [[test-logger entries] (collecting-logger {:min-level :trace})]
    (info test-logger :agent :agent/task-started {})
    (debug test-logger :loop :inner/iteration-started {:data {:n 1}})
    @entries)

  :leave-this-here)
