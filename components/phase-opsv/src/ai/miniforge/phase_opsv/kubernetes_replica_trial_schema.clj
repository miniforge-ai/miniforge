;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-schema :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-settings-schema :as settings]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Provider
  (mu/assoc settings/VerificationSettings :kubernetes/context schema/NonBlankString))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Input
  (-> restoration/Input
      (mu/dissoc :restoration/provider)
      (mu/assoc :trial/provider Provider)
      (mu/assoc :replicas/count replicas/Count)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple retained/Context Input])

(comment
  (count Input))
