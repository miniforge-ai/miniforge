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
(ns ai.miniforge.phase-opsv.verification-result
  "Verify and attach OPSV operational policy evidence."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.confidence :as confidence]
   [ai.miniforge.phase-opsv.evaluation :as evaluation]
   [ai.miniforge.phase-opsv.flow :as flow]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} criteria
  [pack]
  (let [declared (:experiment-pack/success-criteria pack)]
    (if (contains? declared :criteria)
      (:criteria declared)
      (->> declared
           (sort-by (comp str key))
           (mapv (fn [[criterion-id expected]]
                   {:criterion/id (name criterion-id)
                    :criterion/expected expected}))))))

(defn- ^{:stratum 0} attach-verification
  [run synthesized verification]
  (let [summary (select-keys verification [:passed? :confidence :caveats])
        policy (assoc (:opsv/operational-policy synthesized)
                      :operational-policy/verification-summary summary)
        validated (opsv/validate-operational-policy policy)]
    (if (anomaly/anomaly? validated)
      validated
      (assoc synthesized
             :opsv/verification-result verification
             :opsv/verification-run run
             :opsv/operational-policy validated
             :opsv/policy-hash (content-hash/content-hash validated)
             :opsv/metric-snapshot-artifact-refs
             (:metric-snapshot-artifact-refs run)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-policy
  [pack run]
  (let [observations (:observations run)
        score (:confidence run)
        threshold (get-in pack [:experiment-pack/convergence
                                :confidence-threshold])
        confidence-level (confidence/level score threshold)]
    (opsv/verify-policy (criteria pack) observations
                        evaluation/criterion-evaluation confidence-level [])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} output [synthesized run]
  (let [verification (verify-policy (:opsv/experiment-pack synthesized) run)]
    (flow/continue verification (partial attach-verification run synthesized))))

(comment
  (criteria {:experiment-pack/success-criteria {:latency 100}}))
