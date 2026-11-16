;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-authority
  "Monotonic run admission. No operation changes full stop back to active."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.run-publication :as publication]
            [ai.miniforge.execution-grant.run-storage :as storage]
            [ai.miniforge.execution-grant.run-transition :as transition]
            [ai.miniforge.execution-grant.store-codec :as codec]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-directory [root create? id operation]
  (let [directory (storage/directory root create? id)]
    (if (anomaly/anomaly? directory) directory
        (transition/call-with-exception-handling! directory id operation))))

(defn- ^{:stratum 0} admitted [id current]
  (cond
    (anomaly/anomaly? current) current
    (nil? current) (storage/failure id :unauthorized :run/not-registered)
    :else current))

(defn- ^{:stratum 0} register-at! [id now directory]
  (let [reserved (storage/reserve-with-exception-handling! directory id)
        current (when-not (anomaly/anomaly? reserved) (storage/read-record directory id))]
    (cond
      (anomaly/anomaly? reserved) reserved
      (anomaly/anomaly? current) current
      (= :full-stop (:authority/state current)) (storage/failure id :unauthorized :run/stopped)
      current (storage/confirm! directory current)
      (not reserved) (storage/failure id :unauthorized :run/not-registered)
      :else (publication/write-with-exception-handling!
             directory (codec/normalize {:workflow-run/id id
                                         :authority/state :active
                                         :authority/created-at now}) false))))

(defn- ^{:stratum 0} stop-at! [request directory]
  (let [reserved (storage/reserve-with-exception-handling! directory (:workflow-run/id request))
        current (when-not (anomaly/anomaly? reserved) (storage/read-record directory (:workflow-run/id request)))]
    (cond
      (anomaly/anomaly? reserved) reserved
      (anomaly/anomaly? current) current
      (= :full-stop (:authority/state current)) (storage/confirm! directory current)
      :else (publication/write-with-exception-handling!
             directory (codec/normalize (assoc request :authority/state :full-stop)) true))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} register! [root id now]
  (with-directory root true id (partial register-at! id now)))

(defn ^{:stratum 1} current [root id]
  (with-directory root false id #(admitted id (storage/read-record % id))))

(defn ^{:stratum 1} stop! [root request]
  (with-directory root true (:workflow-run/id request) (partial stop-at! request)))
