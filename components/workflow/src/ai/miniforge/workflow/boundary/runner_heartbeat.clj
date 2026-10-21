;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.runner-heartbeat
  "Preserve critical heartbeat failures while retaining legacy warning behavior."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attempt! [operation warning]
  (try+ (operation)
    (catch Object _
      (let [throwable (:throwable &throw-context)]
        (when-let [fatal (cleanup/fatal-cause throwable)] (cleanup/propagate! fatal))
        (println (messages/t warning {:error (ex-message throwable)}))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start!
  ([stream context phase] (start! stream context phase {}))
  ([stream context phase opts]
   (when (dispatch/stateful-stream? stream)
     (attempt! (partial events/start-heartbeat! stream (:execution/id context) phase opts)
               :warn/heartbeat-start))))

(defn ^{:stratum 1} stop! [handle]
  (attempt! (partial events/stop-heartbeat! handle) :warn/heartbeat-stop))

(comment ::heartbeat-effect-boundary)
