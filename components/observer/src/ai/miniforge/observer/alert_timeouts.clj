;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-timeouts
  "Claim and render outstanding tool-call timeouts once."
  (:require [ai.miniforge.observer.alert-conditions :as conditions]
            [ai.miniforge.observer.alert-presentation :as presentation]
            [ai.miniforge.observer.boundary.alert-clock :as clock]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} mark-timeout! [state rule data]
  (swap! state assoc-in [:outstanding-calls (:call-id data) :alerted?] true)
  (presentation/alert rule data))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evaluate [rule event state]
  (let [threshold (get-in rule [:alert/threshold :threshold-ms])
        now (clock/event-ms event)]
    (->> (:outstanding-calls @state)
         (keep (partial conditions/timeout threshold now))
         (mapv (partial mark-timeout! state rule)))))

(comment ::tool-timeout-alerts)
