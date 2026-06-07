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

(ns ai.miniforge.task-executor.bridge
  "Pure event translation between PR lifecycle and DAG scheduler vocabularies.

  Maps PR event types (e.g., :pr/ci-passed) to scheduler actions (e.g., :ci-passed).
  This enables the PR lifecycle controller to communicate state changes back to the
  DAG scheduler without coupling the two components.")

(def pr-event->scheduler-action
  {:pr/opened                    :pr-opened
   :pr/ci-passed                 :ci-passed
   :pr/ci-failed                 :ci-failed
   :pr/review-approved           :review-approved
   :pr/review-changes-requested  :review-changes-requested
   :pr/fix-pushed                :fix-pushed
   :pr/merged                    :merged
   :pr/closed                    :merge-failed
   :pr/conflict                  :ci-failed     ; reuse retry logic
   :pr/rebase-needed             :ci-failed})   ; reuse retry logic

(defn translate-event
  [{:keys [event/type task-id] :as pr-event}]
  (when-let [action (get pr-event->scheduler-action type)]
    {:event/action action
     :event/task-id task-id
     :timestamp (:timestamp pr-event)
     :metadata (dissoc pr-event :event/type :task-id :timestamp)}))

(defn create-scheduler-event
  [task-id action & [opts]]
  (merge
    {:event/action action
     :event/task-id task-id
     :timestamp (or (:timestamp opts) (java.time.Instant/now))}
    (when-let [metadata (:metadata opts)]
      {:metadata metadata})))

(defn unmapped-event?
  [event-type]
  (nil? (get pr-event->scheduler-action event-type)))
