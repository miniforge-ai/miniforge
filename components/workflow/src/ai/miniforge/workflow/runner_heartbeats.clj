;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-heartbeats
  "Own sequential runner phase handles; retain refused cleanup for final retry."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.runner-events :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retain! [handles phase handle]
  (swap! handles assoc phase handle)
  nil)

(defn ^{:stratum 0} phase-context
  "Capture the activation now; late reports must not look up a newer handle."
  [handles context phase]
  (let [handle (get @handles phase)
        clean (dissoc context :execution/report-progress!)]
    (if (:heartbeat/current? handle)
      (assoc clean :execution/report-progress! (partial stream/observe-heartbeat-progress! handle))
      clean)))

(defn ^{:stratum 0} stop! [handles phase]
  (let [result (when-let [handle (get @handles phase)]
                 (events/stop-phase-heartbeat! handle))]
    (if (anomaly/any-anomaly? result)
      result
      (do (swap! handles dissoc phase) nil))))

(defn- ^{:stratum 0} followed-by [remaining operation]
  (partial cleanup/finish! operation remaining))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acquire! [stream handles context phase opts]
  (let [handle (events/start-phase-heartbeat! stream context phase opts)]
    (cond
      (anomaly/any-anomaly? handle) handle
      (nil? handle) nil
      :else (retain! handles phase handle))))

(defn- ^{:stratum 1} stop-and-record! [handles failures phase]
  (when-let [failure (stop! handles phase)]
    (vswap! failures conj failure)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [stream handles context phase opts]
  (when phase
    (let [failure (stop! handles phase)]
      (if failure failure (acquire! stream handles context phase opts)))))

(defn ^{:stratum 2} stop-all! [handles]
  (let [failures (volatile! [])
        operations (map #(partial stop-and-record! handles failures %) (keys @handles))
        release-all (reduce followed-by (constantly nil) operations)]
    (release-all)
    (first @failures)))

(comment ::phase-resource-ownership)
