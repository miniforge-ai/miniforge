;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-experiment-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-retained-recovery-schema :as recovery]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} millis-per-second "Kubernetes worker deadlines use whole seconds." 1000)

(def ^{:stratum 0} Limits
  (-> (mu/get grant/OpsvExperimentScope :opsv/experiment)
      (mu/select-keys [:kubernetes/context :opsv/traffic-destinations :opsv/duration-ms :opsv/load-limits])
      (mu/assoc :effect/id :uuid)))

(def ^{:stratum 0} RetainedBaseline
  [:map [:environment/raw-observations
         [:map [:kubernetes/verification
                [:map [:kubernetes/source [:map [:kubernetes/inventory recovery/Inventory]]]]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} maximum-duration-ms
  "Native work cannot exceed the existing temporary-grant lifetime ceiling."
  (* millis-per-second (get-in grant/issuance-policies [:opsv/experiment :policy/ttl-seconds])))

(comment ::one-native-proposal-bound-and-retained-original-vocabulary)
