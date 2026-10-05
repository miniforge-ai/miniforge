;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-publication
  "Typed admission followed by durable acknowledgment and ordered delivery."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.publication-input :as input]
            [ai.miniforge.event-stream.current-delivery :as delivery]
            [ai.miniforge.event-stream.current-view :as view]
            [ai.miniforge.event-stream.publication :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} active? [stream] (contains? @stream :publication))

(defn ^{:stratum 0} publish! [stream event]
  (let [prepared (input/prepare event)]
    (if (anomaly/any-anomaly? prepared) prepared
        (let [[scope draft] prepared
              receipt (publication/publish! stream scope draft (partial delivery/deliver! stream))]
          (if (anomaly/any-anomaly? receipt) receipt (view/event receipt))))))

(comment
  ::current-publication)
