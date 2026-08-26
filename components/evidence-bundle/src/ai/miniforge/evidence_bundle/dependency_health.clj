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
(ns ai.miniforge.evidence-bundle.dependency-health
  "Dependency health, reconstructed either from recorded state or from
   the event stream when state does not carry it."
  (:require
   [ai.miniforge.evidence-bundle.collectors :as collectors]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private dependency-event-types
  #{:dependency/health-updated
    :dependency/recovered})

(def ^{:stratum 0} ^:private dependency-health-keys
  [:dependency/id
   :dependency/source
   :dependency/kind
   :dependency/status
   :dependency/failure-count
   :dependency/window-size
   :dependency/incident-counts
   :dependency/vendor
   :dependency/class
   :dependency/retryability
   :failure/class
   :dependency/last-observed-at
   :dependency/last-recovered-at])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} canonical-dependency-entry
  [dependency-id dependency]
  (let [entity (select-keys dependency dependency-health-keys)
        canonical-id (or dependency-id (:dependency/id entity))]
    (when canonical-id
      (assoc entity :dependency/id canonical-id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} canonical-dependency-health
  [dependency-health]
  (into {}
        (keep (fn [[dependency-id dependency]]
                (when-let [entry (canonical-dependency-entry dependency-id dependency)]
                  [(:dependency/id entry) entry])))
        dependency-health))

(defn ^{:stratum 2} dependency-health-from-events
  [stream workflow-id]
  (->> dependency-event-types
       (mapcat #(collectors/collect-event-stream-events stream
                                             {:workflow-id workflow-id
                                              :event-type %}))
       (reduce (fn [projection event]
                 (if-let [entry (canonical-dependency-entry (:dependency/id event) event)]
                   (assoc projection (:dependency/id entry) entry)
                   projection))
               {})))
