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

(ns ai.miniforge.agent-runtime.error-classifier.messages
  "User-facing error message generation.

   Formats classified errors into user-friendly messages with context,
   suggestions, and appropriate visual indicators."
  (:require
   [clojure.string :as str]))

;;------------------------------------------------------------------------------ Layer 0
;; Completed work formatting

(defn format-completed-work-section
  "Format completed work items for display.

   Arguments:
     completed-work - Vector of work item strings
     icon-prefix - Icon/prefix for each item

   Returns: Formatted string with work items"
  [completed-work icon-prefix]
  (when (seq completed-work)
    (str "\n\nPartial work completed:\n"
         (str/join "\n" (map #(str icon-prefix " " %) completed-work)))))

;;------------------------------------------------------------------------------ Layer 1
;; Error type specific formatting

(defn format-agent-backend-error
  "Format agent backend error message.

   Arguments:
     classified-error - Map with :message, :completed-work, :report-url, :should-retry

   Returns: Formatted string for display"
  [{:keys [message completed-work report-url should-retry]}]
  (str "⚠️  Agent System Error (Not Your Fault!)\n\n"
       (when (seq completed-work)
         (str "Your task completed successfully, but there was an error\n"
              "in Claude Code's agent runtime after the work was done.\n\n"))
       "Error: " message "\n"
       (format-completed-work-section completed-work "✅")
       "\n\n"
       (if should-retry
         "Report this bug and try again later."
         "No need to retry - your work is complete.")
       (when report-url
         (str "\n\nReport this issue:\n" report-url))))

(defn format-task-code-error
  "Format task code error message.

   Arguments:
     classified-error - Map with :message, :completed-work

   Returns: Formatted string for display"
  [{:keys [message completed-work]}]
  (str "❌ Task Code Error\n\n"
       "Error: " message "\n"
       (format-completed-work-section completed-work "⏸️ ")
       "\n\nFix the issue and retry your task."))

(defn format-external-error
  "Format external service error message.

   Arguments:
     classified-error - Map with :message, :completed-work

   Returns: Formatted string for display"
  [{:keys [message completed-work]}]
  (str "⚠️  External Service Error\n\n"
       "Error: " message "\n"
       (format-completed-work-section completed-work "✅")
       "\n\nWait a few minutes and retry. This is usually transient."))

;;------------------------------------------------------------------------------ Layer 2
;; Main formatting function

(defn format-error-message
  "Generate user-friendly error message with context.

   Arguments:
     classified-error - Map from classify-error with :type, :message, etc.

   Returns: Formatted string for display to user"
  [{:keys [type] :as classified-error}]
  (case type
    :agent-backend (format-agent-backend-error classified-error)
    :task-code (format-task-code-error classified-error)
    :external (format-external-error classified-error)
    (str "❌ Error: " (:message classified-error))))

(defn add-suggestions
  "Add troubleshooting suggestions to error message.

   Arguments:
     message - Base error message
     error-type - Classification keyword

   Returns: Message with added suggestions"
  [message error-type]
  (let [suggestions (case error-type
                      :agent-backend "\n\nTroubleshooting:\n- This is a bug in the agent system\n- Your code is fine\n- Report the issue and retry later"
                      :task-code "\n\nTroubleshooting:\n- Check the error details above\n- Fix the code issue\n- Run tests locally before retrying"
                      :external "\n\nTroubleshooting:\n- Wait a few minutes\n- Check service status\n- Retry with exponential backoff"
                      "")]
    (str message suggestions)))
