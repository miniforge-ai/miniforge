;; Copyright 2025 miniforge.ai
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
(ns ai.miniforge.web-dashboard.state.workflows
  "Workflow state from live and historical event streams."
  (:require
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [ai.miniforge.web-dashboard.state.workflows.events
    :refer [->instant dedupe-events event-order event-ts workflow-id=]]
   [ai.miniforge.web-dashboard.state.workflows.sources
    :refer [historical-events live-stream-events]]
   [ai.miniforge.web-dashboard.state.workflows.summaries :refer [compute-workflows]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} matches-event-type? [event-type event]
  (or (nil? event-type) (= event-type (:event/type event))))

(defn- ^{:stratum 0} matches-since? [since-inst event]
  (or (nil? since-inst)
      (when-let [evt-inst (->instant (event-ts event))]
        (not (.isBefore evt-inst since-inst)))))

(defn- ^{:stratum 0} matches-workflow? [workflow-id event]
  (or (nil? workflow-id) (workflow-id= workflow-id event)))

(def ^{:stratum 0} get-workflows
  "Get workflows from live event stream + disk archive (cached 5s).
   Merging both sources ensures workflows running in a separate process
   (e.g. bb miniforge run) are visible in the dashboard."
  (let [ttl-ms 5000
        cache  (atom {})]
    (fn [state]
      (let [now    (System/currentTimeMillis)
            cached (get @cache [state])]
        (if (and cached (< (- now (:time cached)) ttl-ms))
          (:value cached)
          (let [result (compute-workflows state)]
            (swap! cache assoc [state] {:value result :time now})
            result))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} filter-events
  "Filter and sort events newest-first.

   Options:
   - :workflow-id  Filter by workflow UUID or string id
   - :event-type   Filter by event type keyword
   - :since        Filter events after this timestamp
   - :limit        Max events to return (default 100)"
  [events {:keys [workflow-id event-type since limit] :or {limit 100}}]
  (let [since-inst (when since (->instant since))]
    (->> events
         dedupe-events
         (filter (fn [event]
                   (and (matches-workflow? workflow-id event)
                        (matches-event-type? event-type event)
                        (matches-since? since-inst event))))
         (sort-by event-order #(compare %2 %1))
         (take limit)
         vec)))

(defn ^{:stratum 1} get-workflow-detail
  "Get workflow detail by string id."
  [state id]
  (let [workflows (get-workflows state)]
    (or (first (filter #(= (event-files/canonical-id (:id %)) (event-files/canonical-id id))
                       workflows))
        {:error "Workflow not found"})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} get-events
  "Query raw events from live memory plus archived event files.

   Options:
   - :workflow-id  Filter by workflow UUID or string id
   - :event-type   Filter by event type keyword
   - :since        Filter events after this timestamp
   - :limit        Max events to return (default 100)"
  [state {:keys [workflow-id] :as opts}]
  (try
    (let [all-events (into (live-stream-events state)
                           (historical-events workflow-id))]
      (filter-events all-events opts))
    (catch Exception e
      (println "Error querying events:" (ex-message e))
      [])))

;; Workflow command queue
