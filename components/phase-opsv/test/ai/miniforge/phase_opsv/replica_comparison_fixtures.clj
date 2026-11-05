;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.replica-comparison-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} other-template [] (hash/content-hash :unapproved-template))

(defn ^{:stratum 0} replica [expected id]
  (let [target (:replica/target expected)
        template (:replica/template-hash expected)
        pool (first (:replica/node-pools expected))
        images (:replica/image-digests expected)]
    {:replica/id id
     :replica/target target
     :replica/template-hash template
     :replica/node-pool pool
     :replica/image-digests images}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request [plan stage ids]
  (let [pin (phase/pin-verification-environment plan)
        expected (get-in plan [:comparison/replica-contract stage])
        replicas (mapv (partial replica expected) ids)
        baseline (:comparison/baseline plan)
        fingerprint (cond-> baseline (= stage :candidate) (merge (:comparison/changes plan)))
        request (environment/request pin stage fingerprint)]
    (assoc request :comparison/replica-observations replicas)))

(comment ::declared-population-values-not-native-acquisition-proof)
