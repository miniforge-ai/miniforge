;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-proposal-schema
  (:require [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-schema :as request]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input
  [:map {:closed true}
   [:declaration native/Declaration]
   [:request request/Request]
   [:comparison/artifact-ref :uuid]
   [:current replica/Capture]
   [:parameters request/Parameters]
   [:limits experiment/Limits]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple retained/Context Input])

(comment ::explicit-runtime-bounds-reuse-the-public-n10-scope-contract)
