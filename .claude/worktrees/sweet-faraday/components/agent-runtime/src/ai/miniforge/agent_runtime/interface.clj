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

(ns ai.miniforge.agent-runtime.interface
  "Agent runtime public interface.

   Provides error classification and graceful error handling for agent
   backend errors vs. task code errors vs. external service errors.

   Core workflow:
   1. Catch an exception during task execution
   2. Classify the error: (classify-error exception task-state)
   3. Format user message: (format-error-message classified)
   4. Display to user with appropriate guidance

   Error types:
   - :agent-backend - Agent system bugs (Claude Code, not user's fault)
   - :task-code     - Task execution errors (user's code/spec issues)
   - :external      - External service errors (GitHub, network, transient)"
  (:require
   [ai.miniforge.agent-runtime.error-classifier.core :as core]
   [ai.miniforge.agent-runtime.error-classifier.messages :as messages]
   [ai.miniforge.agent-runtime.error-classifier.reporting :as reporting]))

;------------------------------------------------------------------------------ Layer 0
;; Error classification

(def classify-error
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
  core/classify-error)

;------------------------------------------------------------------------------ Layer 1
;; Message formatting

(def format-error-message
  "Generate user-friendly error message with context.

   Arguments:
     classified-error - Map from classify-error

   Returns: Formatted string for display to user

   Example:
     (format-error-message {:type :agent-backend
                           :message \"classifyHandoffIfNeeded is not defined\"
                           :completed-work [...]
                           :report-url \"...\"
                           :should-retry false})
     => \"⚠️  Agent System Error (Not Your Fault!)...\""
  messages/format-error-message)

(def extract-completed-work
  "Extract completed work items from task state.

   Arguments:
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
  core/extract-completed-work)

;------------------------------------------------------------------------------ Layer 2
;; Reporting and retry logic

(def generate-report-url
  "Generate vendor-specific issue reporting URL with pre-filled context.

   Arguments:
     error-type - Classification keyword (:agent-backend, :task-code, :external)
     vendor     - Vendor name string
     error-context - Map with :message, :task-id, :timestamp, etc.

   Returns: String URL for issue reporting

   Example:
     (generate-report-url :agent-backend \"Claude Code\"
                         {:message \"classifyHandoffIfNeeded is not defined\"
                          :task-id \"abc123\"})
     => \"https://github.com/anthropics/claude-code/issues/new?title=...\""
  reporting/get-vendor-report-url)

(defn should-retry?
  "Determine if user should retry based on error type.

   Arguments:
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
  [{:keys [type completed-work]}]
  (core/should-retry? type completed-work))

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
