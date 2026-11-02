;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-capture-test-support
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as references]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as settings]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as captured]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json])
  (:import [java.net URI]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pods-path "/api/v1/namespaces/staging/pods")

(def ^{:stratum 0} config-path "/api/v1/namespaces/staging/configmaps/settings")

(def ^{:stratum 0} secret-path "/api/v1/namespaces/staging/secrets/credentials")

(defn ^{:stratum 0} path [options] (.getPath (URI. (:uri options))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} routes [optional?]
  (let [snapshot (captured/snapshot)
        pods (references/api-pods optional?)
        cluster (:kubernetes/cluster snapshot)
        namespace (:kubernetes/namespace snapshot)
        node (first (get-in snapshot [:kubernetes/nodes "items"]))
        [configuration secret] (:kubernetes/configuration snapshot)]
    {pods-path pods
     "/api/v1/namespaces/kube-system" cluster
     "/api/v1/namespaces/staging" namespace
     "/api/v1/nodes/worker-a" node
     config-path configuration
     secret-path secret}))

(defn- ^{:stratum 1} reply! [routes calls options]
  (let [path (path options)
        value (get routes path)]
    (swap! calls conj options)
    (cond
      (instance? Throwable value) (wire/failed-future value)
      (not (contains? routes path)) (wire/completed (wire/response "" {:status 404}))
      :else (wire/completed (wire/response (json/generate-string value))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe! [routes overrides]
  (let [calls (atom [])]
    (with-redefs [transport/start! (partial reply! routes calls)
                  transport/response-fields identity]
      [(phase/observe-kubernetes-fingerprint! (settings/settings overrides)) @calls])))

(comment ::native-api-shaped-capture-fixtures)
