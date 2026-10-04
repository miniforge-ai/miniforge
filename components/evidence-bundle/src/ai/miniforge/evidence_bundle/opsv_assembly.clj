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
   [ai.miniforge.evidence-bundle.opsv-assembly-state :as state]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-store
  "Create an in-memory store for run-scoped OPSV assembly records."
  []
  (atom {}))

(defn ^{:stratum 0} restore-store
  "Restore an in-memory store from one durable OPSV assembly record."
  [assembly]
  (atom {(:evidence-bundle/id assembly) assembly}))

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
          assembly (state/initial-record bundle-id workflow-id)
          [old-state _new-state]
          (swap-vals! store state/insert-if-absent bundle-id assembly)]
      (if (contains? old-state bundle-id)
        (recur)
        assembly))))

(defn ^{:stratum 0} get-assembly
  "Return an assembly record or finalized record by preallocated identifier."
  [store bundle-id]
  (get @store bundle-id))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} accumulate!
  "Accumulate immutable-reference material while an OPSV run is active."
  [store bundle-id material]
  (let [[old-state new-state]
        (swap-vals! store state/accumulate bundle-id material)
        old-assembly (get old-state bundle-id)]
    (cond
      (nil? old-assembly)
      (anomaly :anomalies/not-found "OPSV evidence assembly not found"
               bundle-id [{:code :assembly-not-found}])

      (not= :assembling (:opsv.assembly/status old-assembly))
      (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
               bundle-id [{:code :bundle-already-finalized}])

      :else (get new-state bundle-id))))

(comment
  (create-store))
