;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-events
  "Construct current heartbeat drafts without translating legacy ticks into progress."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.core :as legacy]
            [ai.miniforge.event-stream.current-publication :as current]
            [ai.miniforge.event-stream.heartbeat-fields-spec :as fields]
            [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.event-stream.workflow-envelope :as envelope]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-draft [stream workflow-id phase payload]
  (let [draft (envelope/create stream :workflow/phase-heartbeat workflow-id
                               (messages/t :phase/heartbeat {:phase phase}) {})]
    (if (anomaly/any-anomaly? draft) draft
        (merge payload draft {:workflow/phase phase :event/version fields/payload-version}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-heartbeat [stream workflow-id phase payload]
  (if (current/active? stream)
    (current-draft stream workflow-id phase payload)
    (legacy/phase-heartbeat stream workflow-id phase payload)))

(comment ::heartbeat-contract)
