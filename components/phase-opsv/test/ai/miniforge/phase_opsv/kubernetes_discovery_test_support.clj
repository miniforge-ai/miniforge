;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-discovery-test-support
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-client :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.prometheus-client :as prometheus]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as target]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as captured]
            [ai.miniforge.phase-opsv.measurement-test-support :as metric]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} labels
  {"cluster" "staging-cluster"
   "namespace" "staging"
   "service" "catalog"})

(defn- ^{:stratum 0} record! [calls kind value]
  (swap! calls conj kind)
  (when (instance? InterruptedException value) (.interrupt (Thread/currentThread)))
  (if (instance? Throwable value) (throw value) value))

(defn ^{:stratum 0} configuration []
  {:discovery/service "catalog"
   :discovery/kubernetes (target/settings)
   :discovery/prometheus (wire/settings)
   :discovery/metric-labels {:cluster "cluster"
                           :namespace "namespace"
                           :service "service"}
   :discovery/queries {:cpu (metric/request {:prometheus/query "cpu_usage"})
                       :backlog (metric/request {:prometheus/query "queue_depth"})}})

(defn ^{:stratum 0} observation [request labels]
  (let [series (assoc (metric/series) "metric" labels)
        response (metric/response [series])]
    (phase/decode-measurement-window request response)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} kubernetes! [scenario calls _settings]
  (record! calls :kubernetes (:kubernetes scenario)))

(defn- ^{:stratum 1} prometheus! [scenario calls _settings request]
  (let [kind (get {"cpu_usage" :cpu "queue_depth" :backlog} (:prometheus/query request))]
    (record! calls kind (get scenario kind))))

(defn ^{:stratum 1} scenario []
  (let [queries (:discovery/queries (configuration))]
    {:kubernetes (phase/decode-kubernetes-fingerprint (captured/snapshot))
     :cpu (observation (:cpu queries) labels)
     :backlog (observation (:backlog queries) labels)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} discover! [configuration scenario]
  (let [calls (atom [])]
    (with-redefs [kubernetes/observe-fingerprint! (partial kubernetes! scenario calls)
                  prometheus/observe-window! (partial prometheus! scenario calls)]
      [(phase/discover-kubernetes-signals! configuration) @calls])))

(comment ::producer-shaped-discovery-port-fixtures)
