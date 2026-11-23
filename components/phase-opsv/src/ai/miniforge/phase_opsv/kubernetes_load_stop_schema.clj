;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input
  [:map {:closed true}
   [:recovery/input plan/Input] [:recovery/artifact-ref :uuid]
   [:ownership/runtime ownership/OwnershipRuntime] [:effect/id :uuid]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple retained/Context Input])

(comment ::no-caller-provided-plan-pod-uid-revision-payload-or-approval-flag)
