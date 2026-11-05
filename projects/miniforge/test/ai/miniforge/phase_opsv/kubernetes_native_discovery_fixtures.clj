;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-native-discovery-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.prometheus-client :as prometheus]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-discovery-test-support :as discovery]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} configuration []
  (-> (discovery/configuration)
      (update :discovery/kubernetes dissoc :kubernetes/pod-selector)
      (assoc :discovery/declaration (inventory/declaration)
             :discovery/replica-bounds (fingerprint/bounds 1 3))))

(defn- ^{:stratum 0} desired-count [count resource]
  (assoc-in resource ["spec" "replicas"] count))

(defn ^{:stratum 0} changed-configuration [responses]
  (-> responses
      (assoc-in [wire/config-path "data" "workers"] "8")
      (assoc-in [wire/config-path "metadata" "resourceVersion"] "43")))

(defn- ^{:stratum 0} pending-pod [pod]
  (assoc pod "status" {"phase" "Pending"}))

(defn ^{:stratum 0} measurement-calls [calls]
  (filterv :measurement/driver calls))

(defn ^{:stratum 0} kubernetes-calls [calls]
  (filterv :method calls))

(defn- ^{:stratum 0} observe-metric! [measurements calls settings request]
  (let [kind (get {"cpu_usage" :cpu "queue_depth" :backlog} (:prometheus/query request))
        result (get measurements kind)]
    (swap! calls conj {:measurement/driver kind
                      :settings settings
                      :request request})
    (when (instance? InterruptedException result) (.interrupt (Thread/currentThread)))
    (if (instance? Throwable result) (throw result) result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [configuration responses measurements]
  (let [calls (atom [])]
    (with-redefs [transport/start! (partial wire/reply! responses calls)
                  transport/response-fields identity
                  prometheus/observe-window! (partial observe-metric! measurements calls)]
      [(phase/discover-kubernetes-verification-signals! configuration) @calls])))

(defn ^{:stratum 1} routes []
  (-> (native/routes)
      (update inventory/deployment-path (partial desired-count 2))
      (update-in [inventory/replica-sets-path "items"] (partial mapv (partial desired-count 1)))))

(defn ^{:stratum 1} missing-runtime [responses]
  (update-in responses [inventory/pods-path "items"] (partial mapv pending-pod)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input []
  (let [configuration (configuration)
        responses (routes)
        [captured _calls] (native/capture! responses {})
        projected (phase/project-kubernetes-environment-fingerprint captured (:discovery/replica-bounds configuration))
        measurements (select-keys (discovery/scenario) [:cpu :backlog])]
    {:configuration configuration
     :routes responses
     :projected projected
     :measurements measurements}))

(comment ::actual-native-capture-and-decoded-metric-producers)
