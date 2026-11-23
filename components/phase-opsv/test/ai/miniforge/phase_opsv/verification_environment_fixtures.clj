;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment-fixtures
  (:require [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} declaration [request]
  (assoc (select-keys request [:candidate/hash :experiment-pack/hash])
         :runtime/target (:runtime/target (environment/verification-binding))
         :comparison/changes (environment/changes)))

(defn ^{:stratum 0} context [declaration]
  {:execution/id (:workflow/id (environment/verification-binding))
   :execution/opts {:opsv/verification-environment declaration}})

(defn ^{:stratum 0} observation [fingerprint]
  {:environment-fingerprint fingerprint
   :runtime/target (:runtime/target (environment/verification-binding))})

(comment ::declaration-and-observation-have-independent-origins)
