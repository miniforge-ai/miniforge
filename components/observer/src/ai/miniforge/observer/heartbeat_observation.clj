;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.heartbeat-observation
  "Retain explicit availability and the last skipped version without losing position."
  (:require [ai.miniforge.observer.boundary.heartbeat-progress :as progress]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} record! [state event]
  (let [value (progress/interpret event)
        position (select-keys event [:event/id :event/version :event/sequence-number
                                     :scope/type :workflow/id :workflow/phase
                                     :heartbeat/activation-id])
        skipped? (= :unsupported-version (:progress/reason value))]
    (swap! state merge (cond-> {:heartbeat/progress value :heartbeat/position position}
                        skipped? (assoc :heartbeat/last-skipped position)))
    (not skipped?)))

(comment ::version-skip-observation)
