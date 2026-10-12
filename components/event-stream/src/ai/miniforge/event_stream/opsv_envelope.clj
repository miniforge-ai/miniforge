;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-envelope
  "Keep OPSV creation compatible with the stream's publication profile."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.core :as core]
            [ai.miniforge.event-stream.current-publication :as current]
            [ai.miniforge.event-stream.envelope-draft :as draft]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-draft [stream event-type workflow-id message opts]
  (let [event (draft/create stream event-type workflow-id message opts)]
    (if (anomaly/any-anomaly? event) event (assoc event :scope/type :workflow))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create [stream event-type workflow-id message opts]
  (if (current/active? stream)
    (current-draft stream event-type workflow-id message opts)
    (core/create-envelope stream event-type workflow-id message opts)))

(comment
  ::opsv-envelope-profile)
