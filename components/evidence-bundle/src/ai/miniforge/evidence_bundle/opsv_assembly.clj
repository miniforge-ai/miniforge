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
(ns ai.miniforge.evidence-bundle.opsv-assembly
  "Run-scoped N6 OPSV evidence accumulation and immutable finalization."
  (:require
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private reference-keys
  [:opsv/event-refs :opsv/artifact-refs :opsv/capability-refs])

(defn ^{:stratum 0} create-store
  "Create an in-memory store for run-scoped OPSV assembly records."
  []
  (atom {}))

(defn- ^{:stratum 0} anomaly
  [category message bundle-id errors]
  (response/make-anomaly category message
                         {:opsv/evidence-bundle-id bundle-id
                          :opsv.validation/errors errors}))

(defn ^{:stratum 0} allocate!
  "Allocate and store the evidence identifier before the first OPSV event."
  [store workflow-id]
  (loop []
    (let [bundle-id (random-uuid)
          assembly {:evidence-bundle/id bundle-id
                    :evidence-bundle/workflow-id workflow-id
                    :opsv.assembly/status :assembling
                    :opsv/event-refs #{}
                    :opsv/artifact-refs #{}
                    :opsv/capability-refs #{}
                    :opsv/governed-effects #{}}
          [old-state _new-state]
          (swap-vals! store #(if (contains? % bundle-id)
                               %
                               (assoc % bundle-id assembly)))]
      (if (contains? old-state bundle-id)
        (recur)
        assembly))))

(defn ^{:stratum 0} get-assembly
  "Return an assembly record or finalized record by preallocated identifier."
  [store bundle-id]
  (get @store bundle-id))

(defn- ^{:stratum 0} reference-collection
  [value]
  (cond
    (nil? value) []
    (or (sequential? value) (set? value)) value
    :else [value]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} merge-references
  [assembly material]
  (-> (reduce (fn [result key]
                (update result key into
                        (reference-collection (get material key))))
              assembly
              reference-keys)
      (update :opsv/governed-effects into
              (reference-collection
               (get-in material [:opsv/actuation :governed-effects])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} accumulate!
  "Accumulate immutable-reference material while an OPSV run is active."
  [store bundle-id material]
  (let [[old-state new-state]
        (swap-vals! store
                    (fn [state]
                      (if-let [assembly (get state bundle-id)]
                        (if (= :assembling (:opsv.assembly/status assembly))
                          (assoc state bundle-id
                                 (merge-references assembly material))
                          state)
                        state)))
        old-assembly (get old-state bundle-id)]
    (cond
      (nil? old-assembly)
      (anomaly :anomalies/not-found "OPSV evidence assembly not found"
               bundle-id [{:code :assembly-not-found}])

      (not= :assembling (:opsv.assembly/status old-assembly))
      (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
               bundle-id [{:code :bundle-already-finalized}])

      :else (get new-state bundle-id))))
