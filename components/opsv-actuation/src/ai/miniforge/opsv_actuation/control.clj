;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.control
  "Monotonic mutation admission. Stopping never cancels an already admitted effect."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.fences :as fences]
            [ai.miniforge.opsv-actuation.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} boundary-failure []
  (anomaly/anomaly :unavailable (msg/ts :execution/boundary-unconfirmed) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute! [fence operation]
  (if (fences/admit! fence)
    (try
      (operation)
      (catch InterruptedException _
        (let [failure (boundary-failure)]
          (.interrupt (Thread/currentThread))
          failure))
      (catch Throwable _
        (boundary-failure))
      (finally (fences/release! fence)))
    (anomaly/anomaly :unavailable (msg/ts :execution/stopped) {})))

(comment
  (execute! (fences/create) (constantly :done)))
