(ns ai.miniforge.agent-runtime.error-classifier
  "Classify errors by source and generate user-friendly messages.

   Layer 0: Error pattern matching and classification
   Layer 1: Message generation and formatting
   Layer 2: Reporting URL generation and retry logic

   Distinguishes three error types:
   - :agent-backend - Agent system errors (vendor bug, not user's fault)
   - :task-code     - Task execution errors (user/task code issues)
   - :external      - External service errors (network, API, transient)"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Error pattern matching and classification

(def ^:private agent-backend-patterns
  "Regex patterns indicating agent backend/runtime errors."
  [#"is not defined$"
   #"classifyHandoff"
   #"^ReferenceError:"
   #"^TypeError: .* is not a function"
   #"null.*toolResult"
   #"agent.*undefined"
   #"handoff.*failed"
   #"Cannot read property.*undefined"
   #"agentCleanup"])

(def ^:private task-code-patterns
  "Regex patterns indicating task code or compilation errors."
  [#"(?i)syntax error"
   #"(?i)compilation failed"
   #"(?i)linting.*error"
   #"(?i)test.*failed"
   #"(?i)could not resolve"
   #"(?i)no such file"
   #"(?i)unexpected token"
   #"(?i)parse error"])

(def ^:private external-patterns
  "Regex patterns indicating external service errors."
  [#"ECONNREFUSED"
   #"(?i)network error"
   #"(?i)rate limit"
   #"(?i)API.*unavailable"
   #"(?i)timeout"
   #"502.*Bad Gateway"
   #"503.*Service Unavailable"
   #"Connection refused"
   #"(?i)DNS.*failed"])

(defn- matches-pattern?
  "Check if message matches any pattern in the list."
  [message patterns]
  (some #(re-find % message) patterns))

(defn- classify-error-type
  "Classify error by matching against known patterns.
   Returns one of: :agent-backend, :task-code, :external"
  [error-message]
  (cond
    (matches-pattern? error-message agent-backend-patterns) :agent-backend
    (matches-pattern? error-message external-patterns) :external
    (matches-pattern? error-message task-code-patterns) :task-code
    ;; Default to task-code when uncertain (conservative approach)
    :else :task-code))

(defn- error-type->vendor
  "Map error type to responsible vendor."
  [error-type]
  (case error-type
    :agent-backend "Claude Code"
    :task-code "miniforge"
    :external "External Service"
    "Unknown"))

(defn extract-completed-work
  "Extract completed work items from task state.

   Args:
     task-state - Map with task execution state (may be nil)

   Returns: Vector of work item descriptions

   Example:
     (extract-completed-work {:files-created 4
                              :lines-written 523
                              :tests-passed true
                              :pr-url \"https://...\"})
     => [\"Created 4 files (523 lines)\"
         \"All tests passed\"
         \"PR created at https://...\"]"
  [task-state]
  (if-not task-state
    []
    (let [items (cond-> []
                  (:files-created task-state)
                  (conj (str "Created " (:files-created task-state) " files"
                            (when (:lines-written task-state)
                              (str " (" (:lines-written task-state) " lines)"))))

                  (and (:tests-run task-state)
                       (or (true? (:tests-passed task-state))
                           (zero? (:tests-failed task-state 0))))
                  (conj (str "All tests passed"
                            (when (:test-assertions task-state)
                              (str " (" (:test-assertions task-state) " assertions)"))))

                  (and (:tests-failed task-state)
                       (pos? (:tests-failed task-state)))
                  (conj (str (:tests-failed task-state) " tests failed"))

                  (:pr-url task-state)
                  (conj (str "PR created: " (:pr-url task-state)))

                  (:pr-merged task-state)
                  (conj "PR merged successfully")

                  (:branch-created task-state)
                  (conj (str "Created branch: " (:branch-created task-state)))

                  (:commits-made task-state)
                  (conj (str (:commits-made task-state) " commits made")))]
      (vec items))))

;------------------------------------------------------------------------------ Layer 1
;; Message generation and formatting

(defn- format-work-items
  "Format completed work items with checkmarks."
  [work-items]
  (when (seq work-items)
    (str "\n\nYour task completed successfully:\n"
         (str/join "\n" (map #(str "  ✅ " %) work-items)))))

(defn- format-agent-backend-message
  "Generate message for agent backend errors."
  [error-message work-items]
  (str "⚠️  Agent System Error (Not Your Fault!)"
       (format-work-items work-items)
       "\n\nHowever, an error occurred in the Claude Code agent system"
       (if (seq work-items)
         " after your work was done"
         "")
       ":\n\n"
       "  Error: " error-message "\n\n"
       "This is a bug in Claude Code's agent runtime, not in your task or miniforge.\n"
       (if (seq work-items)
         "Your work is complete and safe.\n"
         "")))

(defn- format-task-code-message
  "Generate message for task code errors."
  [error-message work-items]
  (str "❌ Task Code Error\n\n"
       "The task failed during execution due to a code error:\n\n"
       "  Error: " error-message "\n\n"
       "This is an issue with the code being generated or the task specification.\n"
       (when (seq work-items)
         (str "\nPartial work completed:\n"
              (str/join "\n" (map #(str "  ⏸️  " %) work-items))
              "\n"))))

(defn- format-external-message
  "Generate message for external service errors."
  [error-message work-items]
  (str "⚠️  External Service Error\n\n"
       "The task failed due to an external service issue:\n\n"
       "  Error: " error-message "\n\n"
       "This is not an issue with your code or miniforge. The external service\n"
       "is temporarily unavailable.\n"
       (when (seq work-items)
         (str "\nPartial work completed:\n"
              (str/join "\n" (map #(str "  ✅ " %) work-items))
              "\n"))))

(defn format-error-message
  "Generate user-friendly error message with context.

   Args:
     classified-error - Map from classify-error

   Returns: Formatted string for display to user

   Example:
     (format-error-message {:type :agent-backend
                           :message \"classifyHandoffIfNeeded is not defined\"
                           :completed-work [...]
                           :report-url \"...\"
                           :should-retry false})
     => \"⚠️  Agent System Error (Not Your Fault!)...\""
  [classified-error]
  (let [{:keys [type message completed-work report-url should-retry]} classified-error
        work-items (or completed-work [])
        base-message (case type
                       :agent-backend (format-agent-backend-message message work-items)
                       :task-code (format-task-code-message message work-items)
                       :external (format-external-message message work-items)
                       (str "Error: " message))]
    (str base-message
         "\n"
         (when report-url
           (str "📝 Please report this to " (error-type->vendor type) ":\n"
                "   " report-url "\n\n"))
         (if should-retry
           "🔄 Recommendation: "
           "⚙️  ")
         (case type
           :agent-backend (if (seq work-items)
                           "No need to retry - your task succeeded."
                           "Report this bug and try again later.")
           :task-code "Fix the issue and retry the task."
           :external "Wait a few minutes and retry - this is likely transient."
           "Check the error and decide if retry is appropriate."))))

;------------------------------------------------------------------------------ Layer 2
;; Reporting URL generation and retry logic

(defn- url-encode
  "Simple URL encoding for query parameters."
  [s]
  (-> s
      (str/replace " " "+")
      (str/replace "\n" "%0A")
      (str/replace ":" "%3A")
      (str/replace "/" "%2F")))

(defn generate-report-url
  "Generate vendor-specific issue reporting URL with pre-filled context.

   Args:
     error-type - Classification keyword (:agent-backend, :task-code, :external)
     vendor     - Vendor name string
     error-context - Map with :message, :task-id, :timestamp, etc.

   Returns: String URL for issue reporting

   Example:
     (generate-report-url :agent-backend \"Claude Code\"
                         {:message \"classifyHandoffIfNeeded is not defined\"
                          :task-id \"abc123\"})
     => \"https://github.com/anthropics/claude-code/issues/new?title=...\""
  [error-type vendor error-context]
  (let [{:keys [message task-id timestamp]} error-context
        title (case error-type
                :agent-backend (str "Agent Backend Error: " (first (str/split message #"\n")))
                :task-code (str "Task Error: " (first (str/split message #"\n")))
                :external (str "External Service Error: " (first (str/split message #"\n")))
                "Error Report")
        body (str "**Error Type:** " (name error-type) "\n"
                  "**Error Message:**\n```\n" message "\n```\n\n"
                  (when task-id (str "**Task ID:** " task-id "\n"))
                  (when timestamp (str "**Timestamp:** " timestamp "\n"))
                  "\n**Context:**\n"
                  "Please provide any additional context about what you were doing when this error occurred.\n")
        base-url (case vendor
                   "Claude Code" "https://github.com/anthropics/claude-code/issues/new"
                   "miniforge" "https://github.com/miniforge-ai/miniforge/issues/new"
                   nil)]
    (when base-url
      (str base-url "?title=" (url-encode title) "&body=" (url-encode body)))))

(defn should-retry?
  "Determine if user should retry based on error type.

   Args:
     classified-error - Map with :type and :completed-work

   Returns: Boolean indicating if retry is recommended

   Logic:
   - :agent-backend - Don't retry if work completed, otherwise maybe
   - :task-code     - Yes, after fixing the code
   - :external      - Yes, transient issues often resolve

   Example:
     (should-retry? {:type :agent-backend :completed-work [\"PR created\"]})
     => false

     (should-retry? {:type :external})
     => true"
  [classified-error]
  (let [{:keys [type completed-work]} classified-error]
    (case type
      :agent-backend (not (seq completed-work))
      :task-code true
      :external true
      false)))

(defn classify-error
  "Classify an error by matching against known patterns.

   Args:
     error - Exception or error message string
     task-state - Optional map with task execution state

   Returns: Map with classification and metadata
     {:type :agent-backend | :task-code | :external
      :vendor string
      :message string
      :original-error Exception or string
      :completed-work [work-items]
      :report-url string (may be nil)
      :should-retry boolean}

   Example:
     (classify-error (ex-info \"classifyHandoffIfNeeded is not defined\" {})
                     {:files-created 4 :pr-url \"...\"})
     => {:type :agent-backend
         :vendor \"Claude Code\"
         :message \"classifyHandoffIfNeeded is not defined\"
         :completed-work [\"Created 4 files\" \"PR created: ...\"]
         :report-url \"https://github.com/anthropics/claude-code/issues/new?...\"
         :should-retry false}"
  [error task-state]
  (let [error-message (if (instance? Exception error)
                       (or (ex-message error) (str error))
                       (str error))
        error-type (classify-error-type error-message)
        vendor (error-type->vendor error-type)
        completed-work (extract-completed-work task-state)
        error-context {:message error-message
                      :task-id (:task-id task-state)
                      :timestamp (str (java.util.Date.))}
        report-url (when (#{:agent-backend :task-code} error-type)
                    (generate-report-url error-type vendor error-context))
        result {:type error-type
                :vendor vendor
                :message error-message
                :original-error error
                :completed-work completed-work
                :report-url report-url}]
    (assoc result :should-retry (should-retry? result))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test agent backend error classification
  (classify-error "classifyHandoffIfNeeded is not defined"
                  {:files-created 4
                   :lines-written 523
                   :pr-url "https://github.com/org/repo/pull/151"
                   :pr-merged true})
  ;; => {:type :agent-backend
  ;;     :vendor "Claude Code"
  ;;     :message "classifyHandoffIfNeeded is not defined"
  ;;     :completed-work ["Created 4 files (523 lines)"
  ;;                      "PR created: https://github.com/org/repo/pull/151"
  ;;                      "PR merged successfully"]
  ;;     :report-url "https://github.com/anthropics/claude-code/issues/new?..."
  ;;     :should-retry false}

  ;; Test task code error
  (classify-error "Syntax error in components/foo.clj:42"
                  {:files-created 2})
  ;; => {:type :task-code
  ;;     :vendor "miniforge"
  ;;     :should-retry true}

  ;; Test external error
  (classify-error "ECONNREFUSED: Connection refused to github.com"
                  {:branch-created "feat/new-feature"})
  ;; => {:type :external
  ;;     :vendor "External Service"
  ;;     :should-retry true}

  ;; Test message formatting
  (def err (classify-error "classifyHandoffIfNeeded is not defined"
                           {:files-created 4
                            :pr-url "https://github.com/org/repo/pull/151"
                            :pr-merged true}))
  (println (format-error-message err))

  :leave-this-here)
