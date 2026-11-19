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
(ns ai.miniforge.web-dashboard.state.workflows.dependencies
  "Dependency health of a workflow, projected from its
   :dependency/health-updated and :dependency/recovered events.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} dependency-event-types
  "Event types that carry canonical dependency-health projections."
  #{:dependency/health-updated :dependency/recovered})

(def ^{:stratum 0} dependency-status-priority
  "Sort dependency issues from most urgent to least urgent."
  {:operator-action-required 0
   :misconfigured 1
   :unavailable 2
   :degraded 3
   :healthy 4})

(def ^{:stratum 0} dependency-active-statuses
  "Statuses that represent dependency trouble rather than healthy state."
  #{:operator-action-required :misconfigured :unavailable :degraded})

(def ^{:stratum 0} dependency-status-severity
  "Severity labels used to summarize dependency health in workflow state."
  {:operator-action-required :error
   :misconfigured :error
   :unavailable :error
   :degraded :warning
   :healthy :success})

(defn- ^{:stratum 0} assoc-some
  "Assoc only non-nil key/value pairs."
  [m & kvs]
  (reduce (fn [acc [k v]]
            (if (nil? v)
              acc
              (assoc acc k v)))
          m
          (partition 2 kvs)))

(defn- ^{:stratum 0} dependency-id
  [event]
  (or (:dependency/id event)
      (:dependency/vendor event)
      (:dependency/source event)))

(defn- ^{:stratum 0} dependency-sort-label
  [dependency]
  (or (:dependency/vendor dependency)
      (some-> (:dependency/id dependency) str)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} dependency-event?
  [event]
  (contains? dependency-event-types (:event/type event)))

(defn- ^{:stratum 1} dependency-status-rank
  [status]
  (get dependency-status-priority status Long/MAX_VALUE))

(defn- ^{:stratum 1} dependency-entity
  [event]
  (let [{:dependency/keys [status source kind vendor class retryability
                           failure-count window-size incident-counts
                           last-observed-at last-recovered-at]} event
        message (:event/message event)]
    (assoc-some {:dependency/id (dependency-id event)
                 :dependency/status (or status :healthy)}
                :dependency/source            source
                :dependency/kind              kind
                :dependency/vendor            vendor
                :dependency/class             class
                :dependency/retryability      retryability
                :dependency/failure-count     failure-count
                :dependency/window-size       window-size
                :dependency/incident-counts   incident-counts
                :dependency/last-observed-at  last-observed-at
                :dependency/last-recovered-at last-recovered-at
                :dependency/message           message)))

(defn- ^{:stratum 1} dependency-active?
  [dependency]
  (contains? dependency-active-statuses (:dependency/status dependency)))

(defn ^{:stratum 1} dependency-severity
  [dependency-issues]
  (some-> dependency-issues first :dependency/status dependency-status-severity))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} workflow-dependency-health
  "Project the latest dependency-health entities from workflow events."
  [events]
  (->> events
       (filter dependency-event?)
       (reduce (fn [health event]
                 (if-let [dependency-id' (dependency-id event)]
                   (assoc health dependency-id' (dependency-entity event))
                   health))
               {})))

(defn ^{:stratum 2} active-dependencies
  [dependency-health]
  (->> (vals dependency-health)
       (filter dependency-active?)
       (sort-by (juxt #(dependency-status-rank (:dependency/status %))
                      dependency-sort-label))
       vec))
