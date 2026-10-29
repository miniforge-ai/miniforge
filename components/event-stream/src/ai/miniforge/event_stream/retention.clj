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
(ns ai.miniforge.event-stream.retention
  "Bounded in-memory history for long-lived legacy event streams.

   A stream created with a `:retention` policy keeps, for the
   `:max-workflows` workflows that published most recently, every event
   whose type is in `:lifecycle-types`, the newest event of a
   `:latest-per-dependency-types` type for each dependency, and the newest
   `:max-events-per-workflow` other events. Older workflows leave the
   in-memory history entirely. Events without a `:workflow/id` share one
   bucket (dependency-health events are emitted without one). Retention shapes only what `get-events` returns: sinks and
   subscribers still receive every event.

   Compaction runs once the history grows `:max-events-per-workflow`
   events past its size after the previous compaction, so its cost is
   spread over that many publishes."
  (:require
   [ai.miniforge.event-stream.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} bounded-policy
  "Retention for a stream that lives for days, such as the web dashboard's.
   500 per workflow covers the dashboard's largest per-workflow query
   (the 500-event evidence view); 100 workflows covers its 50-workflow
   list. The lifecycle types are what a workflow summary is built from;
   for dependency health only the newest event per dependency is the
   projection, so older ones count against the cap."
  {:max-workflows               100
   :max-events-per-workflow     500
   :lifecycle-types             #{:workflow/started :workflow/resumed
                                  :workflow/completed :workflow/failed :workflow/cancelled
                                  :workflow/phase-started :workflow/phase-completed}
   :latest-per-dependency-types #{:dependency/health-updated :dependency/recovered}})

(defn- ^{:stratum 0} retain-step
  "Reduction step over events newest-first. Admits a workflow while fewer
   than `max-workflows` are admitted, then keeps an admitted workflow's
   lifecycle events, its newest dependency-health event per dependency
   (keyed as the dashboard projects it), and its first
   `max-events-per-workflow` others."
  [{:keys [max-workflows max-events-per-workflow lifecycle-types latest-per-dependency-types]}
   {:keys [workflows counts dependencies] :as acc} event]
  (let [workflow-id (:workflow/id event)
        workflows   (cond-> workflows
                      (< (count workflows) max-workflows) (conj workflow-id))
        kept        (get counts workflow-id 0)
        dependency  (when (contains? latest-per-dependency-types (:event/type event))
                      [workflow-id (or (:dependency/id event) (:dependency/vendor event)
                                       (:dependency/source event))])
        acc         (assoc acc :workflows workflows)]
    (cond
      (not (contains? workflows workflow-id))
      acc

      (contains? lifecycle-types (:event/type event))
      (update acc :kept conj event)

      (and dependency (not (contains? dependencies dependency)))
      (-> acc
          (update :kept conj event)
          (update :dependencies conj dependency))

      (< kept max-events-per-workflow)
      (-> acc
          (update :kept conj event)
          (assoc-in [:counts workflow-id] (inc kept)))

      :else
      acc)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compact
  "Return the events of `events` (a vector in publish order) that `policy`
   retains, still in publish order."
  [events policy]
  (->> (rseq events)
       (reduce (partial retain-step policy)
               {:workflows #{} :counts {} :dependencies #{} :kept ()})
       :kept
       vec))

(defn ^{:stratum 1} init
  "Stream-state entry for the `:retention` option: nil (unbounded) when
   `policy` is nil, else `policy` over `bounded-policy` plus the first
   compaction mark. Throws IllegalArgumentException when a limit is not a
   positive integer (a zero limit would silently drop all history)."
  [policy]
  (when policy
    (let [{:keys [max-workflows max-events-per-workflow] :as policy}
          (merge bounded-policy policy)]
      (when-not (and (pos-int? max-workflows) (pos-int? max-events-per-workflow))
        (throw (IllegalArgumentException.
                ^String (messages/t :retention.system/invalid-limits
                                    {:max-workflows max-workflows
                                     :max-events-per-workflow max-events-per-workflow}))))
      (assoc policy :compact-at max-events-per-workflow))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record
  "Append `event` to the `:events` of stream state `state`, compacting
   under the state's `:retention` once the history passes `:compact-at`."
  [{:keys [retention] :as state} event]
  (let [state (update state :events conj event)]
    (if (and retention (> (count (:events state)) (:compact-at retention)))
      (let [events (compact (:events state) retention)]
        (assoc state
               :events events
               :retention (assoc retention :compact-at (+ (count events)
                                                          (:max-events-per-workflow retention)))))
      state)))

(comment
  (compact [{:workflow/id 1 :event/type :workflow/started}
            {:workflow/id 1 :event/type :agent/chunk}
            {:workflow/id 1 :event/type :agent/chunk}]
           (init {:max-events-per-workflow 1}))
  :end)
