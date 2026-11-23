;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-effect-gate
  "API surface class 1: runtime N4 gate for exact bounded recovery effects."
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.recovery-effect-scope :as scope]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused []
  (let [message (msg/ts :recovery/effect-admission-required)
        remediation {:action :review-exact-recovery-admission
                     :summary message}]
    (response/error message {:data {:reason-code :recovery/effect-admission-required
                                   :remediation remediation}})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check [proposal ctx]
  (let [operations (:opsv/recovery-effect-operations ctx)
        operation (get-in proposal [:opsv/experiment :opsv/operation])]
    (if (and (set? operations) (contains? operations operation)
             (scope/allowed? proposal (:opsv/recovery-plan ctx) (:opsv/recovery-ownership ctx)))
      (response/success {})
      (refused))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} gate/get-gate :opsv/recovery-effect-gate [_]
  {:name :opsv/recovery-effect-gate
   :description (msg/ts :recovery/effect-admission-required)
   :check check
   :repair nil})

(gate/register-gate! :opsv/recovery-effect-gate)

(comment ::plan-approval-alone-cannot-enable-an-exact-recovery-effect)
