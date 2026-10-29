;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-delivery
  "Keep current alerts as observer values; legacy alert events remain unchanged."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.observer.alerts :as alerts]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} deliver! [stream workflow-id state alert]
  (if (events/current-event-stream? stream)
    (swap! state assoc-in [:alerts/latest (:alert/rule-id alert)] alert)
    (events/publish! stream (assoc (alerts/alert-fired nil workflow-id alert) :workflow/id workflow-id))))

(comment ::profile-aware-alert-delivery)
