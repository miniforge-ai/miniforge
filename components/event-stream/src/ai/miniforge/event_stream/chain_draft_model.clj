;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-draft-model
  "Validate current chain payloads before allocating event identity."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.chain-spec :as spec]
            [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.event-stream.schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private envelope-fields
  (-> (set (map first (m/children schema/EventEnvelope)))
      (disj :workflow/id)
      (conj :scope/type)))

(defn- ^{:stratum 0} invalid [event-type]
  (anomaly/anomaly :invalid-input (messages/system :chain/invalid-draft) {:event/type event-type}))

(defn- ^{:stratum 0} candidate [event-type payload]
  (assoc payload :event/type event-type :event/version "2.0.0" :scope/type :chain))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} payload-fields? [payload]
  (and (map? payload) (not-any? envelope-fields (keys payload))))

(defn- ^{:stratum 1} validated [event-type payload]
  (let [value (candidate event-type payload)]
    (if (m/validate spec/Payload value) value (invalid event-type))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [event-type payload]
  (if (payload-fields? payload) (validated event-type payload) (invalid event-type)))

(comment
  (prepare :chain/started {}))
