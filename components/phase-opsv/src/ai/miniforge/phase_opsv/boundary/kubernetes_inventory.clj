;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-inventory
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-list-reads :as lists]
            [ai.miniforge.phase-opsv.boundary.kubernetes-membership :as membership]
            [ai.miniforge.phase-opsv.boundary.kubernetes-resource-reads :as reads]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private root-resource-count
  "Namespace and Deployment are observed before listing their namespace."
  2)

(defn- ^{:stratum 0} admit-settings [declaration settings]
  (let [target (:runtime/target declaration)]
    (cond
      (not (m/validate schema/Target declaration))
      (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-inventory-target) {})
      (not= [(:cluster target) (:namespace target)]
            [(:kubernetes/cluster-id settings) (:kubernetes/namespace settings)]) (membership/mismatch)
      :else settings)))

(defn- ^{:stratum 0} reserve [settings used]
  (let [remaining (- (:kubernetes/max-resources settings) used)]
    ;; Kubernetes interprets limit=0 as unbounded, so never issue that request.
    (if (pos? remaining)
      (assoc settings :kubernetes/max-resources remaining)
      (response/invalid-response))))

(defn- ^{:stratum 0} read-uid! [settings until [expected request]]
  (anomaly/let-ok [value (reads/read! settings until request)]
    (if (= expected (resource/uid value)) value (membership/mismatch))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} roots [declaration settings until]
  (let [{:keys [namespace namespace-uid workload-uid]} (:runtime/target declaration)]
    (anomaly/let-ok [observed-namespace (read-uid! settings until
                                        [namespace-uid (requests/request "Namespace" nil false namespace)])
                    deployment (read-uid! settings until
                                 [workload-uid (requests/request "Deployment" namespace false
                                                                 (:kubernetes/deployment-name declaration))])]
      {:kubernetes/namespace observed-namespace :kubernetes/deployment deployment})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} capture! [declaration settings until]
  (anomaly/let-ok [_settings (admit-settings declaration settings)
                  budget (reserve settings root-resource-count)
                  root (roots declaration settings until)
                  replica-sets (lists/read! budget until
                                 [(requests/replica-sets-path (:kubernetes/namespace settings)) schema/ReplicaSetList])
                  budget (reserve budget (count (get replica-sets "items")))
                  pods (lists/read! budget until
                         [(requests/pods-path (:kubernetes/namespace settings)) schema/PodList])]
    (let [observed (assoc root :runtime/target (:runtime/target declaration)
                              :kubernetes/cluster-id (:kubernetes/cluster-id settings)
                              :kubernetes/replica-set-inventory replica-sets
                              :kubernetes/pod-inventory pods)]
      (if (inventory/unique-identities? observed) observed (membership/mismatch)))))

(comment ::bounded-namespace-inventory-no-selector-or-owner-derived-paths)
