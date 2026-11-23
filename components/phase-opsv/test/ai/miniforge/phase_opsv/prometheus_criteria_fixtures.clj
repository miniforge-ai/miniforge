;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-discovery-test-support :as discovery]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as native]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [ai.miniforge.phase-opsv.prometheus-criteria :as model]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} bearer-token
  "Non-secret credential fixture for the supported HTTPS bearer-token option."
  "sensitive-endpoint-option")

(defn ^{:stratum 0} expected-observations []
  {"peak" 3.0
   "floor" 1.0
   "tail" 2.0})

(defn ^{:stratum 0} criterion [id reduction]
  (let [request (measurement/request {:prometheus/query "queue_depth"})]
    {:criterion/id id
     :measurement/request request
     :measurement/reduction reduction}))

(defn ^{:stratum 0} response []
  (let [samples [[100 "3"] [110 "1"] [120 "2"]]
        series (assoc (measurement/series samples) "metric" discovery/labels)]
    (measurement/response [series])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configuration []
  (let [settings (wire/settings)
        target (:runtime/target (native/declaration))
        labels (:discovery/metric-labels (discovery/configuration))
        criteria [(criterion "peak" :maximum) (criterion "floor" :minimum) (criterion "tail" :last)]]
    {:measurement/prometheus settings
     :measurement/target target
     :measurement/service "catalog"
     :measurement/label-names labels
     :measurement/criteria criteria}))

(defn ^{:stratum 1} observation []
  (let [request (:measurement/request (criterion "peak" :maximum))]
    (phase/decode-measurement-window request (response))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-credentials []
  (assoc-in (configuration) [:measurement/prometheus :prometheus/bearer-token] bearer-token))

(defn ^{:stratum 2} records [configuration]
  (let [observed (observation)]
    (mapv #(model/record % observed) (:measurement/criteria configuration))))

(defn ^{:stratum 2} with-endpoint-cap [maximum]
  (-> (configuration)
      (assoc-in [:measurement/prometheus :measurement/max-samples] maximum)
      (assoc-in [:measurement/criteria 1 :measurement/request :measurement/max-samples] 4)))

(comment ::actual-decoded-range-vector-shapes)
