;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} fingerprint []
  {:cluster "staging-1"
   :node-pools ["general"]
   :image-digests {"catalog" "sha256:baseline"}
   :config-hash "baseline-config"})

(defn ^{:stratum 0} verification-binding []
  {:workflow/id #uuid "00000000-0000-0000-0000-000000000701"
   :verification/id #uuid "00000000-0000-0000-0000-000000000702"
   :candidate/hash (hash/content-hash :candidate)
   :experiment-pack/hash (hash/content-hash :pack)
   :runtime/target {:cluster "staging-1"
                    :namespace "staging"
                    :namespace-uid "namespace-uid"
                    :workload-uid "deployment-uid"}})

(defn ^{:stratum 0} changes []
  {:config-hash "candidate-config"
   :image-digests {"catalog" "sha256:candidate"}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} plan []
  {:comparison/binding (verification-binding)
   :comparison/baseline (fingerprint)
   :comparison/changes (changes)})

(comment ::canonical-fingerprint-contract-fixtures)
