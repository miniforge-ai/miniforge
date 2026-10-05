;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.delivery
  "Isolate best-effort listener failures after durable acknowledgment."
  (:require [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.logging.interface :as log]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} preserve-critical! [throwable]
  (when-let [cause (critical/cause throwable)] (critical/propagate! cause)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} report! [logger event]
  (when logger
    (try+
      (let [failure (model/failure :fault :publication/listener-failed event)]
        (log/warn logger :event-stream :event/delivery-failed
                  {:message (:anomaly/message failure)
                   :data (:anomaly/data failure)}))
      (catch Object _ (preserve-critical! (:throwable &throw-context))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} invoke! [logger event call]
  (try+
    (call)
    (catch Object _
      (preserve-critical! (:throwable &throw-context))
      (report! logger event))))

(comment
  ::best-effort-delivery)
