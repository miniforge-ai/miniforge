;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fresh-revision "A changed server revision without configuration drift." "fresh-trial-revision")

(def ^{:stratum 0} out-of-bounds-count 0)

(defn- ^{:stratum 0} patch-operation [operation path value]
  {"op" operation
   "path" path
   "value" value})

(defn ^{:stratum 0} prepare [{:keys [ctx] :as fixture} routes]
  (let [calls (atom [])]
    (with-redefs [transport/start! (partial wire/reply! routes calls) transport/response-fields identity]
      [(phase/prepare-kubernetes-replica-trial-proposal! ctx (:trial/request fixture)) @calls])))

(defn ^{:stratum 0} replacement-routes [routes]
  (-> routes
      (assoc-in [inventory/pods-path "items" 0 "metadata" "uid"] "replacement-pod-uid")
      (assoc-in [inventory/pods-path "items" 0 "metadata" "name"] "catalog-replacement")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} expected-patch [fixture]
  (let [uid (get-in fixture [:native :capture :kubernetes/inventory :kubernetes/deployment "metadata" "uid"])
        count (get-in fixture [:trial/request :replicas/count])]
    [(patch-operation "test" "/metadata/uid" uid)
     (patch-operation "test" "/metadata/resourceVersion" fresh-revision)
     (patch-operation "replace" "/spec/replicas" count)]))

(defn- ^{:stratum 1} configured [f {:keys [restoration/request native] :as fixture}]
  (let [provider (settings/load-settings (:restoration/provider request) schema/Provider [:kubernetes/pod-selector])
        trial (-> request
                  (dissoc :restoration/provider)
                  (assoc :trial/provider provider :replicas/count replicas/trial-count))
        routes (assoc-in (:routes native) [inventory/deployment-path "metadata" "resourceVersion"] fresh-revision)]
    (f (assoc fixture :trial/request trial :trial/routes routes))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input [f] (restoration/with-input (partial configured f)))

(comment
  (with-input #(prepare % (:trial/routes %))))
