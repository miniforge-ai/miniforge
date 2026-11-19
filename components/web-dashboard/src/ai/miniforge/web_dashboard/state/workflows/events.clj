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
(ns ai.miniforge.web-dashboard.state.workflows.events
  "Pure helpers over workflow events: timestamps, ids, creation order,
   de-duplication, and the phase, status, metrics and output preview of a
   workflow."
  (:require
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.config :as dashboard-config]
   [ai.miniforge.web-dashboard.event-files :as event-files]))

;------------------------------------------------------------------------------ Layer 0

;; Pure helpers
(def ^{:stratum 0} ^:private defaults dashboard-config/defaults)

(def ^{:stratum 0} phase-lifecycle-types
  "Event types that carry phase classification data."
  #{:workflow/phase-started :workflow/phase-completed})

(def ^{:stratum 0} terminal-statuses
  "Workflow statuses that indicate no further execution."
  #{:completed :failed :cancelled :stale})

(defn ^{:stratum 0} normalize-ts
  "Normalize timestamp inputs to java.util.Date for UI rendering."
  [ts]
  (cond
    (instance? java.util.Date ts)
    ts

    (instance? java.time.Instant ts)
    (java.util.Date/from ts)

    (string? ts)
    (try
      (java.util.Date/from (java.time.Instant/parse ts))
      (catch Exception _ nil))

    :else nil))

(defn ^{:stratum 0} ->instant
  "Normalize timestamp inputs to java.time.Instant for comparisons."
  [ts]
  (cond
    (instance? java.time.Instant ts)
    ts

    (instance? java.util.Date ts)
    (.toInstant ^java.util.Date ts)

    (string? ts)
    (try
      (java.time.Instant/parse ts)
      (catch Exception _ nil))

    :else nil))

(defn ^{:stratum 0} wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn ^{:stratum 0} event-ts
  "Extract the canonical event timestamp."
  [event]
  (or (:event/timestamp event) (:timestamp event)))

(defn ^{:stratum 0} wf-name-from-started
  "Extract a display name for a workflow from its started event."
  [started id]
  (let [spec (or (get started :workflow/spec)
                 (get started :workflow-spec)
                 (get started :spec))]
    (or (get spec :spec/title)
        (get spec :title)
        (get spec :name)
        (str "Workflow " (subs (str id) 0 (min 8 (count (str id))))))))

(defn ^{:stratum 0} event-phase
  "Extract the phase from an event, accepting both qualified and legacy keys."
  [event]
  (or (get event :workflow/phase) (get event :phase)))

(defn ^{:stratum 0} workflow-recency
  "Epoch millis of the most recent workflow activity, for sorting newest-first."
  [wf]
  (or (some-> (:updated-at wf) .getTime)
      (some-> (:started-at wf) .getTime)
      0))

(defn ^{:stratum 0} first-event
  "Return the first event of event-type from events."
  [event-type events]
  (->> events (filter #(= event-type (:event/type %))) first))

(defn ^{:stratum 0} last-event
  "Return the last event of event-type from events."
  [event-type events]
  (->> events (filter #(= event-type (:event/type %))) last))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} stale-threshold-ms
  "Workflows with no events for this long are considered stale (10 minutes)."
  (:stale-threshold-ms defaults))

(def ^{:stratum 1} max-recent-workflows
  "Maximum number of recent workflows returned from the live stream."
  (:max-recent-workflows defaults))

(defn ^{:stratum 1} ts-epoch-ms
  "Timestamp → epoch millis for sorting."
  [ts]
  (if-let [date (normalize-ts ts)]
    (.getTime ^java.util.Date date)
    0))

(defn ^{:stratum 1} workflow-id=
  "Workflow-id equality through `event-files/canonical-id`, so UUID,
   string and keyword forms match."
  [expected event]
  (= (event-files/canonical-id expected)
     (event-files/canonical-id (wf-id event))))

(defn ^{:stratum 1} dedupe-events
  "Deduplicate live + historical events by event-id, falling back to a
   composite key of stable fields when no id is present, keeping the
   first of each in the order seen. Ids compare in their
   `event-files/canonical-id` form."
  [events]
  (first
   (reduce (fn [[kept seen :as acc] event]
             (let [event-key (if-let [id (get event :event/id)]
                               (event-files/canonical-id id)
                               [(get event :event/type)
                                (event-files/canonical-id (wf-id event))
                                (event-ts event)
                                (get event :event/sequence-number)
                                (get event :message)])]
               (if (contains? seen event-key)
                 acc
                 [(conj kept event) (conj seen event-key)])))
           [[] #{}]
           events)))

(defn ^{:stratum 1} workflow-progress
  "Progress percentage for a workflow: 100 when terminal, 50 while running."
  [status]
  (if (terminal-statuses status) 100 50))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} event-order
  "An event's place in creation order: its timestamp, then its
   :event/sequence-number for events of one millisecond. A live event
   has no file, so `event-files/order-key` cannot place it."
  [event]
  [(ts-epoch-ms (event-ts event)) (get event :event/sequence-number 0)])

(defn ^{:stratum 2} wf-phase
  "Derive the current phase of a workflow from its events."
  [events started]
  (or (event-phase started)
      (some->> events
               (filter (comp phase-lifecycle-types :event/type))
               (sort-by #(ts-epoch-ms (event-ts %)))
               last
               event-phase)
      :unknown))

(defn ^{:stratum 2} wf-status
  "Derive a workflow status keyword from terminal events and staleness. A
   cancel is its own outcome (N3 workflow/cancelled), never a failure; an
   older run folded it into :workflow/completed with status :cancelled."
  [completed failed cancelled last-event-ts]
  (cond
    failed     :failed
    cancelled  :cancelled
    completed  (let [status (get completed :workflow/status (get completed :status))]
                 (case status
                   (:success :completed) :completed
                   :cancelled :cancelled
                   :failed))
    :else      (let [last-ts (normalize-ts last-event-ts)]
                 (if (and last-ts
                          (> (- (System/currentTimeMillis) (.getTime ^java.util.Date last-ts))
                             stale-threshold-ms))
                   :stale
                   :running))))

(defn ^{:stratum 2} workflow-metrics
  "Aggregate workflow metrics from terminal and phase completion events.
   Prefers values on the :workflow/completed event; falls back to summing
   individual phase completion events."
  [events]
  (let [terminal (->> events
                      (filter #(= :workflow/completed (:event/type %)))
                      (sort-by #(ts-epoch-ms (event-ts %)))
                      last)
        phases (filter #(= :workflow/phase-completed (:event/type %)) events)
        total-tokens      (reduce + 0 (keep :phase/tokens phases))
        total-cost-usd    (reduce + 0 (keep :phase/cost-usd phases))
        total-duration-ms (reduce + 0 (keep :phase/duration-ms phases))
        tokens      (or (get terminal :workflow/tokens)      (when (pos? total-tokens) total-tokens))
        cost-usd    (or (get terminal :workflow/cost-usd)    (when (pos? total-cost-usd) total-cost-usd))
        duration-ms (or (get terminal :workflow/duration-ms) (when (pos? total-duration-ms) total-duration-ms))]
    (cond-> {}
      tokens      (assoc :tokens tokens)
      cost-usd    (assoc :cost-usd cost-usd)
      duration-ms (assoc :duration-ms duration-ms))))

(defn ^{:stratum 2} workflow-output-preview
  "Build a short preview from the latest streaming chunks."
  [events]
  (some->> events
           (filter #(= :agent/chunk (:event/type %)))
           (sort-by #(ts-epoch-ms (event-ts %)))
           (map :chunk/delta)
           (remove str/blank?)
           (take-last 12)
           (apply str)
           str/trim
           not-empty))

(comment ::workflows-events)
