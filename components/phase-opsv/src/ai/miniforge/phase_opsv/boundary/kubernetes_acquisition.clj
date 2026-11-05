;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-acquisition
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} timeout []
  (anomaly/anomaly :timeout (msg/ts :kubernetes/timeout) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed! [failure]
  (cond
    (cleanup/fatal-cause failure) (cleanup/after-failure! failure (constantly nil))
    (http/timeout-cause failure) (timeout)
    :else (anomaly/anomaly :unavailable (msg/ts :kubernetes/unavailable) {})))

(defn- ^{:stratum 1} acquire! [operation options]
  (let [until (io/deadline (:kubernetes/timeout-ms options))
        result (operation options until)]
    (if (pos? (io/remaining-ms until)) result (timeout))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe!
  "Run a component-owned read operation with validated settings and one deadline."
  [settings-loader overrides operation]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [options (settings-loader overrides)]
      (acquire! operation options))
    (catch Object _ (failed! (:throwable &throw-context)))))

(comment ::shared-deadline-and-failure-boundary-for-read-only-captures)
