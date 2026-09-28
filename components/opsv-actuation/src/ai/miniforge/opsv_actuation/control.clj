;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.control
  "Monotonic mutation admission. Stopping never cancels an already admitted effect."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} MutationFence [state])

(defn ^{:stratum 0} status [fence]
  @(:state fence))

(defn ^{:stratum 0} stop! [fence]
  (swap! (:state fence) assoc :stopped? true))

(defn- ^{:stratum 0} admit! [fence]
  (let [[before _] (swap-vals! (:state fence)
                              #(if (:stopped? %) % (update % :in-flight inc)))]
    (not (:stopped? before))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fence? [value]
  (instance? MutationFence value))

(defn ^{:stratum 1} create []
  (->MutationFence (atom {:stopped? false :in-flight 0})))

(defn ^{:stratum 1} execute! [fence operation]
  (if (admit! fence)
    (try
      (operation)
      (catch Exception _
        (anomaly/anomaly :unavailable (msg/ts :execution/boundary-unconfirmed) {}))
      (finally (swap! (:state fence) update :in-flight dec)))
    (anomaly/anomaly :unavailable (msg/ts :execution/stopped) {:opsv/stopped? true})))

(comment
  (status (create)))
