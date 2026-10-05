;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-delivery
  "Ordered best-effort listeners; durability belongs to the publication journal."
  (:require [ai.miniforge.event-stream.boundary.delivery :as boundary]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} filtered-call! [callback filter-fn event]
  (when (filter-fn event) (callback event)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} deliver! [stream event]
  (let [{:keys [sinks subscribers filters logger]} @stream]
    (doseq [sink sinks]
      (boundary/invoke! logger event (partial sink event)))
    (doseq [[id callback] subscribers]
      (boundary/invoke! logger event
                        (partial filtered-call! callback (get filters id (constantly true)) event)))))

(comment
  ::committed-listener-delivery)
