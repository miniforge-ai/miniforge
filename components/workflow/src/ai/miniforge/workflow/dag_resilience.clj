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

(ns ai.miniforge.workflow.dag-resilience
  "DAG checkpoint event emission and resume support.

   Keeps the DAG orchestrator focused on scheduling by extracting
   persistence concerns into this module. Three layers:
   - Layer 0: Rate limit / quota detection from error patterns
   - Layer 1: Checkpoint event emission (task-completed, dag-paused)
   - Layer 2: Resume — reconstruct completed-ids from event file"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;--- Layer 0: Quota / Rate Limit Detection

(def quota-patterns
  "Compiled patterns that indicate provider quota or rate limit exhaustion."
  (delay
    (mapv re-pattern
          ["(?i)you've hit your limit"
           "(?i)rate.?limit"
           "(?i)quota.?exceeded"
           "(?i)429|Too Many Requests"
           "(?i)resets \\d+[ap]m|resets in \\d+"])))

(defn quota-error?
  "Check if a DAG task result indicates a quota/rate limit error."
  [result]
  (when-not (dag/ok? result)
    (let [raw (or (get-in result [:error :message])
                  (get-in result [:error :data :message])
                  (str result))
          msg (if (string? raw) raw (str raw))]
      (boolean (some #(re-find % msg) @quota-patterns)))))

(defn analyze-batch-for-quota
  "Categorize batch results into completed, quota-limited, and other-failed."
  [results]
  (reduce (fn [acc [task-id result]]
            (cond
              (dag/ok? result)
              (update acc :completed-ids conj task-id)

              (quota-error? result)
              (update acc :quota-limited-ids conj task-id)

              :else
              (update acc :other-failed-ids conj task-id)))
          {:completed-ids #{} :quota-limited-ids #{} :other-failed-ids #{}}
          results))

;--- Layer 1: Checkpoint Event Emission

(defn emit-dag-task-completed!
  "Publish :dag/task-completed event for checkpointing.
   Includes the task's artifacts so resume can skip re-execution."
  [event-stream workflow-id task-id result]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/task-completed
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/task-id task-id
                   :dag/result (select-keys result [:data :status])}))
      (catch Exception _ nil))))

(defn emit-dag-task-failed!
  "Publish :dag/task-failed event for observability."
  [event-stream workflow-id task-id result]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/task-failed
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/task-id task-id
                   :dag/error (select-keys result [:error :status])
                   :dag/quota-error? (quota-error? result)}))
      (catch Exception _ nil))))

(defn emit-dag-paused!
  "Publish :dag/paused event with completed task IDs for resume."
  [event-stream workflow-id completed-ids reason]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/paused
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/completed-task-ids (vec completed-ids)
                   :dag/pause-reason reason}))
      (catch Exception _ nil))))

;--- Layer 2: Resume — Reconstruct from Event File

(defn safe-read-edn
  "Read an EDN string, returning nil on parse failure.
   Handles #object, #uuid, and #inst tagged literals gracefully."
  [s]
  (try
    (let [readers (merge {'object (fn [& args] (str "#object" args))
                          'uuid   (fn [s] (java.util.UUID/fromString s))
                          'inst   (fn [s] (java.time.Instant/parse s))}
                         clojure.core/*data-readers*)]
      (edn/read-string {:readers readers :default tagged-literal} s))
    (catch Exception _ nil)))

(defn read-event-file
  "Read all events from a workflow event file.
   Returns a vector of parsed event maps, skipping unparseable lines."
  [file-path]
  (let [f (io/file file-path)]
    (when (.exists f)
      (with-open [rdr (io/reader f)]
        (->> (line-seq rdr)
             (keep (fn [line]
                     (when-not (str/blank? line)
                       (safe-read-edn line))))
             vec)))))

(defn extract-completed-task-ids
  "Extract the set of completed DAG task IDs from a sequence of events.
   Looks for :dag/task-completed events and returns their task IDs."
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (map :dag/task-id)
       set))

(defn extract-completed-artifacts
  "Extract artifacts from completed task events for merging into results."
  [events]
  (->> events
       (filter #(= :dag/task-completed (:event/type %)))
       (mapcat #(get-in % [:dag/result :data :artifacts] []))
       vec))

(def default-events-dir
  "Default directory for workflow event files."
  (delay (str (System/getProperty "user.home") "/.miniforge/events")))

(defn resume-context-from-event-file
  "Build resume context from a workflow's event file.

   Arguments:
   - workflow-id: UUID of the workflow to resume
   - opts: Optional map with :events-dir to override default path

   Returns a map suitable for merging into the DAG execution context:
   {:pre-completed-ids #{task-id-1 task-id-2 ...}
    :pre-completed-artifacts [artifact-1 ...]
    :resumed? true}"
  ([workflow-id] (resume-context-from-event-file workflow-id {}))
  ([workflow-id opts]
   (let [events-dir (or (:events-dir opts) @default-events-dir)
         file-path (str events-dir "/" workflow-id ".edn")
         events (read-event-file file-path)]
    (if (seq events)
      (let [completed-ids (extract-completed-task-ids events)
            artifacts (extract-completed-artifacts events)]
        {:pre-completed-ids completed-ids
         :pre-completed-artifacts artifacts
         :resumed? true
         :resume-source file-path})
      {:pre-completed-ids #{}
       :pre-completed-artifacts []
       :resumed? false}))))
