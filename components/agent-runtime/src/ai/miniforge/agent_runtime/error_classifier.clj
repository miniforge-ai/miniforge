(ns ai.miniforge.agent-runtime.error-classifier
  "Error classification and message formatting for Miniforge agent runtime.

   This namespace provides a facade over the sub-namespaces for backward compatibility.
   The implementation is split into focused sub-namespaces following stratified design:

   - error-classifier.patterns  - Error pattern definitions and matching
   - error-classifier.core      - Core classification logic
   - error-classifier.messages  - User-facing message generation
   - error-classifier.reporting - Vendor reporting and issue tracking

   Classifies errors into three categories:
   - :agent-backend - Bugs in the agent system (Claude Code, not user's fault)
   - :task-code - User code/spec errors
   - :external - External service errors (transient)"
  (:require
   [ai.miniforge.agent-runtime.error-classifier.core :as core]
   [ai.miniforge.agent-runtime.error-classifier.messages :as messages]
   [ai.miniforge.agent-runtime.error-classifier.reporting :as reporting]))

;;------------------------------------------------------------------------------ Public API
;; Delegate to sub-namespaces for backward compatibility

(def classify-error
  "Classify an error by matching against known patterns.

   Arguments:
     error - Exception or error message string
     task-state - Optional map with task execution state

   Returns: Map with classification and metadata"
  core/classify-error)

(def extract-completed-work
  "Extract completed work items from task state.

   Arguments:
     task-state - Map with task execution state (may be nil)

   Returns: Vector of work item descriptions"
  core/extract-completed-work)

(def format-error-message
  "Generate user-friendly error message with context.

   Arguments:
     classified-error - Map from classify-error

   Returns: Formatted string for display to user"
  messages/format-error-message)

(def generate-report-url
  "Generate vendor-specific issue reporting URL with pre-filled context.

   Arguments:
     error-type - Classification keyword (:agent-backend, :task-code, :external)
     vendor     - Vendor name string
     error-context - Map with :message, :task-id, :timestamp, etc.

   Returns: String URL for issue reporting (or nil for external errors)"
  reporting/get-vendor-report-url)

(defn should-retry?
  "Determine if user should retry based on error type.

   Arguments:
     classified-error - Map with :type and :completed-work

   Returns: Boolean indicating if retry is recommended"
  [{:keys [type completed-work]}]
  (core/should-retry? type completed-work))
