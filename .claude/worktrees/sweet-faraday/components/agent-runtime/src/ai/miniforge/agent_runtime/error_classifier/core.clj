(ns ai.miniforge.agent-runtime.error-classifier.core
  "Core error classification logic.

   Orchestrates pattern matching, completed work extraction, and metadata generation."
  (:require
   [ai.miniforge.agent-runtime.error-classifier.patterns :as patterns]
   [ai.miniforge.agent-runtime.error-classifier.reporting :as reporting]))

;;------------------------------------------------------------------------------ Layer 0
;; Error message extraction

(defn extract-error-context
  "Extract relevant context from error for classification.

   Arguments:
     error - Exception or error message string

   Returns: String error message"
  [error]
  (cond
    (instance? Exception error) (ex-message error)
    (string? error) error
    :else (str error)))

;;------------------------------------------------------------------------------ Layer 1
;; Completed work extraction

(defn extract-completed-work
  "Extract completed work items from task state.

   Arguments:
     task-state - Map with task execution state (may be nil)

   Returns: Vector of work item descriptions"
  [task-state]
  (if task-state
    (let [items (atom [])]
      ;; Files created
      (when-let [files (:files-created task-state)]
        (let [lines (:lines-written task-state)
              desc (if lines
                     (str "Created " files " files (" lines " lines)")
                     (str "Created " files " files"))]
          (swap! items conj desc)))

      ;; Tests
      (cond
        (:tests-failed task-state)
        (swap! items conj (str (:tests-failed task-state) " tests failed"))

        (and (:tests-passed task-state) (:test-assertions task-state))
        (swap! items conj (str "All tests passed (" (:test-assertions task-state) " assertions)")))

      ;; Branch created
      (when-let [branch (:branch-created task-state)]
        (swap! items conj (str "Created branch: " branch)))

      ;; PR created
      (when-let [pr-url (:pr-url task-state)]
        (swap! items conj (str "PR created: " pr-url)))

      ;; PR merged
      (when (:pr-merged task-state)
        (swap! items conj "PR merged successfully"))

      ;; Commits
      (when-let [commits (:commits-made task-state)]
        (swap! items conj (str commits " commits made")))

      @items)
    []))

;;------------------------------------------------------------------------------ Layer 2
;; Retry logic

(defn determine-confidence
  "Calculate confidence score for classification.

   Arguments:
     pattern - Matched pattern map
     message - Error message

   Returns: Float confidence score 0.0-1.0"
  [pattern _message]
  ;; Simple implementation - could be enhanced with ML
  (if (:regex pattern)
    0.95
    0.5))

(defn should-retry?
  "Determine if user should retry based on error type.

   Arguments:
     error-type - Classification keyword
     completed-work - Vector of completed work items

   Returns: Boolean indicating if retry is recommended"
  [error-type completed-work]
  (case error-type
    :agent-backend (empty? completed-work)
    :task-code true
    :external true
    true))

;;------------------------------------------------------------------------------ Layer 3
;; Main classification function

(defn classify-error
  "Classify an error by matching against known patterns.

   Arguments:
     error - Exception or error message string
     task-state - Optional map with task execution state

   Returns: Map with classification and metadata
     {:type :agent-backend | :task-code | :external
      :vendor string
      :message string
      :original-error Exception or string
      :completed-work [work-items]
      :report-url string (may be nil)
      :should-retry boolean
      :confidence float}"
  [error task-state]
  (let [message (extract-error-context error)
        pattern (patterns/classify-by-patterns message)
        completed-work (extract-completed-work task-state)
        error-context {:message message
                       :task-id (:task-id task-state)
                       :timestamp (str (java.util.Date.))}
        report-url (reporting/get-vendor-report-url (:type pattern) (:vendor pattern) error-context)
        confidence (determine-confidence pattern message)]
    (merge pattern
           {:message message
            :original-error error
            :completed-work (or completed-work [])
            :report-url report-url
            :should-retry (should-retry? (:type pattern) completed-work)
            :confidence confidence})))
