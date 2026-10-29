;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat-publication
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.current-publication :as publication]
            [ai.miniforge.event-stream.heartbeat-events :as events]
            [ai.miniforge.event-stream.messages :as msg]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} publish! [stream payload]
  (try+
    (publication/publish! stream (events/phase-heartbeat stream (:workflow/id payload)
                                                       (:workflow/phase payload) payload))
    (catch Object failure
      (when-let [fatal (critical/cause (:throwable &throw-context))]
        (critical/propagate! fatal))
      (if (anomaly/any-anomaly? failure) failure
          (anomaly/anomaly :fault (msg/system :heartbeat/emission-failed) {})))))

(comment ::ephemeral-publication-boundary)
