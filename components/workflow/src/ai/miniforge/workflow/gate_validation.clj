;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-validation
  "Project gate decisions and require their audit receipt before phase advancement."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-events :as publication]
            [ai.miniforge.workflow.gate-receipt :as receipt]
            [ai.miniforge.workflow.gate-projection :as projection]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} validate-gates [gates phase-result ctx]
  (let [artifact (response/phase-output phase-result)
        gate-result (gate/check-gates gates artifact ctx)
        minted (gate/gates->envelope gate-result (nil? artifact))
        envelope (when (:envelope/id minted) minted)
        result (projection/decision phase-result gate-result envelope)
        receipt (if envelope (publication/emit-phase-decision! ctx phase-result envelope) (receipt/failure :invalid-envelope))]
    (if (anomaly/any-anomaly? receipt) (projection/publication-failure result receipt) result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate [interceptor phase-result ctx]
  (let [gates (get-in interceptor [:config :gates] [])]
    (if (seq gates) (validate-gates gates phase-result ctx) phase-result)))

(comment
  (validate {} {} {}))
