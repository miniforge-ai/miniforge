;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-proposal-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-schema :as request]
            [ai.miniforge.phase-opsv.kubernetes-retained-recovery-schema :as recovery]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Limits
  (-> (mu/get grant/OpsvExperimentScope :opsv/experiment)
      (mu/select-keys [:kubernetes/context :opsv/traffic-destinations :opsv/duration-ms :opsv/load-limits])
      (mu/assoc :effect/id :uuid)))

(def ^{:stratum 0} RetainedBaseline
  [:map [:environment/raw-observations
         [:map [:kubernetes/verification
                [:map [:kubernetes/source [:map [:kubernetes/inventory recovery/Inventory]]]]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Input
  [:map {:closed true}
   [:declaration native/Declaration]
   [:request request/Request]
   [:comparison/artifact-ref :uuid]
   [:current replica/Capture]
   [:parameters request/Parameters]
   [:limits Limits]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple retained/Context Input])

(comment ::explicit-runtime-bounds-reuse-the-public-n10-scope-contract)
