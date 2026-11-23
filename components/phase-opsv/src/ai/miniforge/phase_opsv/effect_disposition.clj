;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.effect-disposition
  "Shared N3/N6 disposition joins; observed references never confer recovery success.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} reference [transaction]
  {:evidence/effect-id (:effect/id transaction)
   :evidence/grant-id (:effect/grant-id transaction)
   :evidence/envelope-id (:effect/envelope-id transaction)})

(defn ^{:stratum 0} matching-event [expected records]
  (first (filter #(= expected (select-keys % (keys expected))) records)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} payload [transaction envelope observed-keys]
  {:opsv/governed-effect (reference transaction)
   :opsv/effect-state (:effect/state transaction)
   :opsv/decision-envelope envelope
   :opsv/effect-observed (select-keys (:effect/observed transaction) observed-keys)
   :opsv/effect-failure (:effect/failure transaction)})

(comment
  (reference {:effect/id (random-uuid)}))
