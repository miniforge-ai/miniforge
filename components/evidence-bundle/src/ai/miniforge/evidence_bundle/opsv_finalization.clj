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
   [ai.miniforge.evidence-bundle.opsv-assembly :as assembly]
   [ai.miniforge.evidence-bundle.protocols.impl.evidence-bundle :as bundle]
   [ai.miniforge.evidence-bundle.schema.opsv :as schema]
   [ai.miniforge.response.interface :as response]
   [clojure.set :as cset]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} anomaly
  [category message bundle-id errors]
  (response/make-anomaly category message
                         {:opsv/evidence-bundle-id bundle-id
                          :opsv.validation/errors errors}))

(defn- ^{:stratum 0} reference-set
  [value]
  (if (set? value) value (set value)))

(defn- ^{:stratum 0} detailed-artifact-refs
  [evidence]
  (set (concat
        [(:opsv/experiment-pack-artifact-id evidence)]
        (map :artifact-id (:opsv/policy-proposals evidence))
        (get-in evidence [:opsv/actuation :postcondition-artifact-refs])
        (get-in evidence [:opsv/actuation :rollback :artifact-refs])
        (:opsv/metric-query-artifact-refs evidence)
        (:opsv/metric-snapshot-artifact-refs evidence)
        (:opsv/diff-artifact-refs evidence))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reference-errors
  [record evidence available-artifact-ids]
  (let [event-refs (reference-set (:opsv/event-refs evidence))
        artifact-refs (reference-set (:opsv/artifact-refs evidence))
        capability-refs (reference-set (:opsv/capability-refs evidence))
        effects (reference-set (get-in evidence [:opsv/actuation
                                                 :governed-effects]))
        effect-capabilities (set (map :evidence/capability-id effects))]
    (cond-> []
      (not= (:opsv/event-refs record) event-refs)
      (conj {:code :event-reference-mismatch})
      (not= (:opsv/artifact-refs record) artifact-refs)
      (conj {:code :artifact-reference-mismatch})
      (not= (:opsv/capability-refs record) capability-refs)
      (conj {:code :capability-reference-mismatch})
      (not= (:opsv/governed-effects record) effects)
      (conj {:code :governed-effect-mismatch})
      (not (cset/subset? (detailed-artifact-refs evidence) artifact-refs))
      (conj {:code :detailed-artifact-reference-missing})
      (not (cset/subset? artifact-refs (set available-artifact-ids)))
      (conj {:code :referenced-artifact-not-found
             :missing (vec (sort (cset/difference
                                  artifact-refs
                                  (set available-artifact-ids))))})
      (not (cset/subset? effect-capabilities capability-refs))
      (conj {:code :uncorrelated-governed-effect}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize!
  "Publish one immutable N6 bundle using the preallocated identifier."
  [store bundle-id base-bundle evidence available-artifact-ids]
  (let [record (assembly/get-assembly store bundle-id)
        schema-valid? (m/validate schema/OpsvEvidence evidence)
        errors (cond-> []
                 (not schema-valid?)
                 (conj {:code :invalid-opsv-evidence})
                 (and record
                      (not= (:evidence-bundle/workflow-id record)
                            (:evidence-bundle/workflow-id base-bundle)))
                 (conj {:code :workflow-reference-mismatch})
                 (and record schema-valid?)
                 (into (reference-errors record evidence
                                         available-artifact-ids)))
        candidate (-> base-bundle
                      (dissoc :evidence/content-hash)
                      (assoc :evidence-bundle/id bundle-id
                             :evidence/opsv evidence))
        bundle-errors (:errors (bundle/validate-bundle-impl candidate))
        errors (into errors
                     (map #(assoc % :code :invalid-evidence-bundle)
                          bundle-errors))]
    (cond
      (nil? record)
      (anomaly :anomalies/not-found "OPSV evidence assembly not found"
               bundle-id [{:code :assembly-not-found}])
      (not= :assembling (:opsv.assembly/status record))
      (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
               bundle-id [{:code :bundle-already-finalized}])
      (seq errors)
      (anomaly :anomalies/incorrect "OPSV evidence finalization failed"
               bundle-id errors)
      :else
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
          (recur store bundle-id base-bundle evidence available-artifact-ids)
          :else
          (anomaly :anomalies/conflict "OPSV evidence bundle is immutable"
                   bundle-id [{:code :bundle-already-finalized}]))))))
