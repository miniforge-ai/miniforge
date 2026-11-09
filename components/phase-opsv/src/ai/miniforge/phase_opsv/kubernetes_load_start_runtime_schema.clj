;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-runtime-schema
  (:require [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as input]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} HostRuntime
  [:map {:closed true}
   [:effects-directory schema/NonBlankString]
   [:authority-directory schema/NonBlankString]
   [:breach-directory schema/NonBlankString]
   [:clock fn?] [:current-context fn?]
   [:fence actuation/MutationFence] [:control [:fn control/run?]]])

(def ^{:stratum 0} Context
  [:and retained/Context [:map [:execution/status [:= :running]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple HostRuntime input/Input])

(comment
  (count HostRuntime))
