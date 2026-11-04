;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-verification-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as references]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as settings]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} nodes-path "/api/v1/nodes")

(def ^{:stratum 0} cluster-path "/api/v1/namespaces/kube-system")

(defn- ^{:stratum 0} with-template [spec controller]
  (assoc-in controller ["spec" "template" "spec"] (dissoc spec "nodeName")))

(defn ^{:stratum 0} observe! [routes overrides declared]
  (let [calls (atom [])
        base (dissoc (settings/settings) :kubernetes/pod-selector)]
    (with-redefs [transport/start! (partial wire/reply! routes calls)
                  transport/response-fields identity]
      [(phase/observe-kubernetes-verification! (merge base overrides) declared) @calls])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} routes []
  (let [spec (get-in (references/api-pods false) ["items" 0 "spec"])
        nodes (assoc (kube/resource-list "NodeList" [(kube/node)])
                      "metadata" {"resourceVersion" "42"})]
    (-> (merge (wire/routes false) (inventory/routes))
        (assoc nodes-path nodes)
        (update inventory/deployment-path (partial with-template spec))
        (update-in [inventory/replica-sets-path "items"] (partial mapv (partial with-template spec)))
        (update-in [inventory/pods-path "items"] (partial mapv #(update % "spec" merge spec))))))

(defn ^{:stratum 1} capture! [routes overrides]
  (observe! routes overrides (inventory/declaration)))
