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
(ns ai.miniforge.evidence-bundle.opsv-finalization
  "Validate a candidate, then seal it against an unchanged assembly record."
  (:require [ai.miniforge.evidence-bundle.opsv-assembly :as assembly]
            [ai.miniforge.evidence-bundle.opsv-finalization-candidate :as candidate]
            [ai.miniforge.evidence-bundle.opsv-finalization-publication :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} publish-candidate! [store record base evidence available-ids]
  (let [{:keys [bundle errors]} (candidate/prepare record base evidence available-ids)
        bundle-id (:evidence-bundle/id record)]
    (if (seq errors)
      (publication/failure :anomalies/incorrect :finalization/invalid bundle-id errors)
      (publication/publish! store record bundle))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} attempt! [store bundle-id base evidence available-ids]
  (let [record (assembly/get-assembly store bundle-id)]
    (cond
      (nil? record)
      (publication/failure :anomalies/not-found :finalization/not-found bundle-id
                           [{:code :assembly-not-found}])
      (not= :assembling (:opsv.assembly/status record))
      (publication/immutable bundle-id)
      :else (publish-candidate! store record base evidence available-ids))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize!
  "Publish one immutable N6 bundle using the preallocated identifier."
  [store bundle-id base-bundle evidence available-artifact-ids]
  (loop []
    (let [result (attempt! store bundle-id base-bundle evidence available-artifact-ids)]
      (if (= ::publication/retry result) (recur) result))))

(comment
  (finalize! (assembly/create-store) (random-uuid) {} {} #{}))
