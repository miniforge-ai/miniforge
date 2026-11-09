;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-creation-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as recovery]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input
  [:map {:closed true}
   [:recovery/input recovery/Input]
   [:recovery/artifact-ref :uuid]
   [:creation/provider native/Settings]])

(defn ^{:stratum 0} effect-id [input]
  (get-in input [:recovery/input :load :limits :effect/id]))

(comment
  (count Input))
