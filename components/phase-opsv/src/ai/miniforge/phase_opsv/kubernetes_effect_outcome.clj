;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-outcome
  (:require [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [outcome message-key]
  (let [message (msg/ts message-key)]
    {:effect/outcome outcome
     :effect/failure message}))

(defn ^{:stratum 0} acknowledged [observed]
  {:effect/outcome :succeeded
   :effect/observed observed})

(comment ::provider-acknowledgement-never-implies-verified-postconditions)
