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
(ns ai.miniforge.web-dashboard.state.workflows.summaries
  "Workflow summaries built from the merged live and disk events."
  (:require
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [ai.miniforge.web-dashboard.state.workflows.dependencies
    :refer [active-dependencies dependency-severity workflow-dependency-health]]
   [ai.miniforge.web-dashboard.state.workflows.events
    :refer [dedupe-events event-order event-ts first-event last-event
            max-recent-workflows normalize-ts wf-id wf-name-from-started wf-phase
            wf-status workflow-metrics workflow-output-preview workflow-progress
            workflow-recency]]
   [ai.miniforge.web-dashboard.state.workflows.sources
    :refer [historical-events live-stream-events]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attach-dependency-health
  [workflow dependency-health]
  (let [dependency-issues (active-dependencies dependency-health)]
    (cond-> workflow
      (seq dependency-health)
      (assoc :dependency-health dependency-health)

      (seq dependency-issues)
      (assoc :dependency-issues dependency-issues
             :dependency-severity (dependency-severity dependency-issues)
             :failure-attribution (first dependency-issues)))))

;; Data fetchers
(defn- ^{:stratum 0} merged-events
  "Combine in-process live events with disk-archive events, filtering
   out any disk events for workflow IDs already represented in the
   live stream. Avoids duplicating in-process workflow events and
   keeps test isolation when callers use a non-default state."
  [state]
  (let [live-events (live-stream-events state)
        live-wf-ids (into #{} (keep (comp event-files/canonical-id wf-id)) live-events)
        disk-events (->> (historical-events nil)
                         (remove #(live-wf-ids (event-files/canonical-id (wf-id %)))))]
    (into live-events disk-events)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} workflow-summary-from-events
  "Build the workflow summary map for a single workflow from its
   chronologically-ordered events. Returns the summary with
   dependency-health attached."
  [id wf-events]
  (let [wf-events         (->> wf-events (sort-by event-order) vec)
        started           (first-event :workflow/started   wf-events)
        completed         (last-event  :workflow/completed wf-events)
        failed            (last-event  :workflow/failed    wf-events)
        cancelled         (last-event  :workflow/cancelled wf-events)
        status            (wf-status completed failed cancelled (some-> wf-events last event-ts))
        metrics           (workflow-metrics wf-events)
        output-preview    (workflow-output-preview wf-events)
        dependency-health (workflow-dependency-health wf-events)
        summary           (cond-> {:id                 id
                                   :name               (wf-name-from-started started id)
                                   :status             status
                                   :phase              (wf-phase wf-events started)
                                   :progress           (workflow-progress status)
                                   :started-at         (normalize-ts (event-ts started))
                                   :updated-at         (normalize-ts (some-> wf-events last event-ts))
                                   :completed-at       (normalize-ts (event-ts completed))
                                   :event-count        (count wf-events)
                                   :evidence-bundle-id (:workflow/evidence-bundle-id completed)
                                   :metrics            metrics}
                            output-preview (assoc :latest-output output-preview))]
    (attach-dependency-health summary dependency-health)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compute-workflows
  "Build the sorted, capped list of workflow summaries for `state`."
  [state]
  (try
    (let [grouped-events (->> (merged-events state)
                              ;; A watcher rebuild republishes events;
                              ;; metrics must not sum the repeats.
                              dedupe-events
                              (filter (comp some? wf-id))
                              (group-by (comp event-files/canonical-id wf-id)))]
      (->> grouped-events
           (map (fn [[id wf-events]] (workflow-summary-from-events id wf-events)))
           (sort-by workflow-recency >)
           (take max-recent-workflows)
           vec))
    (catch Exception e
      (println "Error getting workflows:" (ex-message e))
      [])))

(comment ::workflows-summaries)
