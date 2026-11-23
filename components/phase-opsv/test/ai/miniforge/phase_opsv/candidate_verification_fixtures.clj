;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.candidate-verification-fixtures
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.phase-opsv.verification-environment-fixtures :as environment]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} observations [fingerprint]
  (assoc (environment/observation fingerprint)
         :environment/raw-observations {:provider/snapshot fingerprint}))

(defn ^{:stratum 0} prepare [calls observed request]
  (swap! calls conj [:prepare request])
  (phase/verification-preparation-receipt request observed))

(defn ^{:stratum 0} measure [calls directory observed request]
  (let [retained (artifact/read-published directory (:comparison/artifact-ref request))]
    (swap! calls conj [:verify request retained])
    (phase/candidate-verification-receipt request (merge support/verification-measurements observed))))

(defn ^{:stratum 0} verify [ctx prepare measure]
  (phase/verify (assoc-in ctx [:execution/opts :opsv/adapter]
                          (phase/functional-adapter identity identity prepare measure))))

(defn ^{:stratum 0} synthesized [ctx]
  (support/phase-output ctx :opsv/synthesize))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-context [f]
  (storage/with-context
    (fn [ctx directory]
      (let [ctx (reduce storage/step ctx (take 5 support/handlers))
            output (synthesized ctx)
            declaration (environment/declaration
                          {:candidate/hash (hash/content-hash (:opsv/operational-policy output))
                           :experiment-pack/hash (hash/content-hash (:opsv/experiment-pack output))})]
        (f (assoc-in ctx [:execution/opts :opsv/verification-environment] declaration)
           directory (:opsv/environment-fingerprint output))))))

(comment ::real-storage-and-assembly-not-simulated-durability)
