;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-criteria
  "Normalize declared criteria and evaluate fresh candidate observations."
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.confidence :as confidence]
            [ai.miniforge.phase-opsv.evaluation :as evaluation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} criterion-entry [[criterion-id expected]]
  {:criterion/id (name criterion-id)
   :criterion/expected expected})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} criteria [pack]
  (let [declared (:experiment-pack/success-criteria pack)]
    (if (contains? declared :criteria)
      (:criteria declared)
      (mapv criterion-entry (sort-by (comp str key) declared)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate [pack run]
  (let [observations (:observations run)
        score (:confidence run)
        threshold (get-in pack [:experiment-pack/convergence :confidence-threshold])
        confidence-level (confidence/level score threshold)]
    (opsv/verify-policy (criteria pack) observations
                        evaluation/criterion-evaluation confidence-level [])))

(comment
  (criteria {:experiment-pack/success-criteria {:latency 100}}))
