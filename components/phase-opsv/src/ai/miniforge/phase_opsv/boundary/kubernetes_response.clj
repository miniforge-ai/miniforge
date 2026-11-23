;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-response
  "Bounded native Kubernetes JSON admission, separate from HTTP lifetime ownership."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.boundary.json-document :as json]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} not-found
  "Explicit absent-resource outcome, distinct from JSON null and transport failure."
  ::not-found)

(defn ^{:stratum 0} invalid-response []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-response) {}))

(defn- ^{:stratum 0} status-failure [status]
  (anomaly/anomaly :unavailable (msg/ts :kubernetes/http-status) {:http/status status}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} document! [settings until body]
  (let [limit (:kubernetes/max-response-bytes settings)
        bytes (io/read-bytes! body limit until)]
    (if (> (alength bytes) limit)
      (invalid-response)
      (json/decode! (io/utf8-text bytes)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe!
  ([settings until pending] (observe! settings until pending #{http/success-status}))
  ([settings until pending accepted-statuses]
   (let [{:keys [status encodings body]} (http/response-fields (io/await! pending until))]
     (cond
       (= http/not-found-status status) not-found
       (not (contains? accepted-statuses status)) (status-failure status)
       (not (http/unencoded? encodings)) (invalid-response)
       :else (document! settings until body)))))

(comment ::bounded-kubernetes-response)
