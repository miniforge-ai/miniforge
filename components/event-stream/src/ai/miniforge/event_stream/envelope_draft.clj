;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.envelope-draft
  "Event identity and metadata construction without reserving a publication position."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.snowflake :as snowflake]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const event-version "1.0.0")

(def ^{:stratum 0} ^:private identity-keys
  [:org/id :workspace/id :repo/id :auth/context :event/parent-id :agent/id :agent/instance-id])

(defn- ^{:stratum 0} next-event-id [generator]
  (cond
    (anomaly/any-anomaly? generator) generator
    generator (snowflake/next-id! generator)
    :else (random-uuid)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} identity-fields [opts]
  (into {} (filter (comp boolean val)) (select-keys opts identity-keys)))

(defn- ^{:stratum 1} envelope [stream event-type workflow-id message]
  (let [event-id (next-event-id (:snowflake-generator @stream))]
    (if (anomaly/any-anomaly? event-id) event-id
        {:event/type event-type
         :event/id event-id
         :event/timestamp (java.util.Date.)
         :event/version event-version
         :workflow/id workflow-id
         :message message})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create
  "Construct a draft, not an acknowledgment. It has no sequence number and is not
   admitted, persisted, redacted, or delivered. Retain it when retrying publication."
  ([stream event-type workflow-id message]
   (create stream event-type workflow-id message {}))
  ([stream event-type workflow-id message opts]
   (let [draft (envelope stream event-type workflow-id message)]
     (if (anomaly/any-anomaly? draft) draft
         (merge draft (identity-fields opts))))))

(comment
  (create (atom {}) :example/created nil "example"))
