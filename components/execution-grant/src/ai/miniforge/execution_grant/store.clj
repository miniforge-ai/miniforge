;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store
  "Compose immutable issuance and revocation records into current authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.core :as core]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.execution-grant.store-io :as storage]
            [ai.miniforge.execution-grant.store-schema :as store-schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} revocation-keys
  [:grant/id :grant/revoked-at :grant/revocation-reason])

(defn ^{:stratum 0} register!
  [dir grant-record]
  (let [id (:grant/id grant-record)
        marker (storage/read-record dir id :revocation store-schema/Revocation)]
    (cond
      (anomaly/anomaly? marker) marker
      marker (anomaly/anomaly :conflict (msg/t :store/id-conflict) {:grant/id id})
      :else (storage/create! dir (codec/normalize grant-record) :grant))))

(defn- ^{:stratum 0} current-record
  [grant-record marker id]
  (cond
    (anomaly/anomaly? grant-record) grant-record
    (anomaly/anomaly? marker) marker
    (nil? grant-record)
    (when marker
      (anomaly/anomaly :fault (msg/t :store/read-failed)
                       {:grant/id id :record/kind :revocation}))
    :else (merge grant-record marker)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} persist-revocation!
  [dir grant-record reason now]
  (let [revoked (codec/normalize (core/revoke grant-record reason now))
        marker (select-keys revoked revocation-keys)
        recorded (storage/create! dir marker :revocation)]
    (if (anomaly/anomaly? recorded) recorded revoked)))

(defn ^{:stratum 1} current
  [dir id]
  (let [grant-record (storage/read-record dir id :grant store-schema/IssuedGrant)
        marker (storage/read-record dir id :revocation store-schema/Revocation)]
    (current-record grant-record marker id)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} revoke!
  [dir id reason now]
  (let [grant-record (current dir id)]
    (cond
      (anomaly/anomaly? grant-record) grant-record
      (nil? grant-record) (anomaly/anomaly :not-found (msg/t :store/not-found) {:grant/id id})
      (:grant/revoked-at grant-record) (storage/confirm! dir grant-record :revocation)
      :else (persist-revocation! dir grant-record reason now))))

(comment
  (current nil (random-uuid)))
