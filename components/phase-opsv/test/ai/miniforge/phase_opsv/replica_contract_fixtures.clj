;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.replica-contract-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-candidate-replicas
  "Allow growth to three replicas while recovery restores one baseline replica."
  3)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} expectation [plan stage]
  (let [target (get-in plan [:comparison/binding :runtime/target])
        baseline (:comparison/baseline plan)
        candidate? (= stage :candidate)
        fingerprint (cond-> baseline candidate? (merge (:comparison/changes plan)))
        template (hash/content-hash stage)
        pools (:node-pools baseline)
        images (:image-digests fingerprint)
        upper (if candidate? maximum-candidate-replicas 1)]
    {:replica/target target
     :replica/template-hash template
     :replica/node-pools pools
     :replica/image-digests images
     :replicas/min-count 1
     :replicas/max-count upper}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} plan []
  (let [base (environment/plan)
        candidate (expectation base :candidate)
        rollback (expectation base :rollback)]
    (assoc base :comparison/replica-contract {:candidate candidate :rollback rollback})))

(comment ::declared-candidate-and-recovery-populations)
