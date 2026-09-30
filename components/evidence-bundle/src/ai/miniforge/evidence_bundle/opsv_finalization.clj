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
  "Validation and exactly-once publication of assembled N6 OPSV evidence."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.evidence-bundle.canonical-validation :as validation]
   [ai.miniforge.evidence-bundle.opsv-assembly :as assembly]
   [ai.miniforge.evidence-bundle.opsv-finalization-references :as references]
   [ai.miniforge.evidence-bundle.schema.opsv :as schema]
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} anomaly
  [category message bundle-id errors]
  (response/make-anomaly category message
                         {:opsv/evidence-bundle-id bundle-id
                          :opsv.validation/errors errors}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finalize!
  "Publish one immutable N6 bundle using the preallocated identifier."
  [store bundle-id base-bundle evidence available-artifact-ids]
  (let [record (assembly/get-assembly store bundle-id)]
    (cond
      (nil? record)
      (anomaly :anomalies/not-found "OPSV evidence assembly not found"
               bundle-id [{:code :assembly-not-found}])
      (not= :assembling (:opsv.assembly/status record))
      (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
               bundle-id [{:code :bundle-already-finalized}])
      :else
      (let [schema-valid? (m/validate schema/OpsvEvidence evidence)
            base-valid? (map? base-bundle)
            canonical-evidence (if schema-valid?
                                 (references/canonicalize evidence)
                                 evidence)
            candidate (when base-valid?
                        (-> base-bundle
                            (dissoc :evidence/content-hash :evidence/signature)
                            (assoc :evidence-bundle/id bundle-id
                                   :evidence/opsv canonical-evidence)))
            errors (cond-> []
                     (not schema-valid?)
                     (conj {:code :invalid-opsv-evidence})
                     (not base-valid?)
                     (conj {:code :invalid-base-bundle})
                     (and base-valid?
                          (not= (:evidence-bundle/workflow-id record)
                                (:evidence-bundle/workflow-id base-bundle)))
                     (conj {:code :workflow-reference-mismatch})
                     schema-valid?
                     (into (references/errors record canonical-evidence
                                             available-artifact-ids))
                     candidate
                     (into (map #(assoc % :code :invalid-evidence-bundle)
                                (:errors (validation/validate-with-exception-handling
                                          candidate)))))]
        (if (seq errors)
          (anomaly :anomalies/incorrect "OPSV evidence finalization failed"
                   bundle-id errors)
          (let [final-bundle (assoc candidate :evidence/content-hash
                                    (content-hash/content-hash candidate))
                [old-state new-state]
                (swap-vals! store update bundle-id
                            (fn [current]
                              (if (= current record)
                                (assoc current
                                       :opsv.assembly/status :finalized
                                       :opsv.assembly/bundle final-bundle)
                                current)))]
            (cond
              (= record (get old-state bundle-id))
              (get-in new-state [bundle-id :opsv.assembly/bundle])
              (= :assembling
                 (get-in old-state [bundle-id :opsv.assembly/status]))
              (recur store bundle-id base-bundle evidence
                     available-artifact-ids)
              :else
              (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
                       bundle-id [{:code :bundle-already-finalized}]))))))))
