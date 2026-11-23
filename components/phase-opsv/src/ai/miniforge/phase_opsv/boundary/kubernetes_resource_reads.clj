;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-resource-reads
  "Verify requested identity and native type before admitting each API resource."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} identity-of [value]
  [(get value "kind") (resource/namespace-of value) (resource/name-of value)])

(defn- ^{:stratum 0} absent [optional? expected]
  (if optional?
    response/not-found
    (assoc (response/invalid-response) :anomaly/data
           {:kubernetes/resource-state :absent
            :kubernetes/expected-identity expected})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted? [schema expected value]
  (and (m/validate schema value) (= expected (identity-of value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read! [settings until [path schema expected optional?]]
  (let [value (http/read-resource! settings until path {})]
    (cond
      (anomaly/anomaly? value) value
      (= response/not-found value) (absent optional? expected)
      (not (admitted? schema expected value)) (response/invalid-response)
      :else value)))

(comment ::identity-bound-resource-read)
