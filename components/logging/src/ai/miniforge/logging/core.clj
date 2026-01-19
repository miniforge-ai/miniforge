(ns ai.miniforge.logging.core
  "Structured EDN logging implementation.
   Layer 0: Pure functions for log entry creation
   Layer 1: Logger protocol and default implementation")

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

(defn create-logger
  "Create a new EDN logger with the given configuration.

   Options:
   - :min-level - Minimum log level to emit (default :trace)
   - :output    - Output mode :edn (default), :human, or custom function
   - :context   - Initial context map"
  ([] (create-logger {}))
  ([{:keys [min-level output context] :or {min-level :trace output :edn}}]
   (let [output-fn (case output
                     :edn default-output-fn
                     :human human-output-fn
                     output)]
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
