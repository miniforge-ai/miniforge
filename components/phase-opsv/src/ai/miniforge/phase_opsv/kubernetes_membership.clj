;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-membership
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-reference :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-identity-consistency :as identity]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unique-identities? [key-fn resources]
  (= (count resources) (count (distinct (map key-fn resources)))))

(defn- ^{:stratum 0} resources [observed]
  (concat [(:kubernetes/namespace observed) (:kubernetes/deployment observed)]
          (:kubernetes/replica-sets observed) (get-in observed [:kubernetes/pods "items"])))

(defn- ^{:stratum 0} same-target? [observed]
  (let [{:keys [cluster namespace namespace-uid workload-uid]} (:runtime/target observed)
        captured-namespace (:kubernetes/namespace observed)
        deployment (:kubernetes/deployment observed)]
    (and (= cluster (:kubernetes/cluster-id observed))
         (= namespace (resource/name-of captured-namespace) (resource/namespace-of deployment))
         (= namespace-uid (resource/uid captured-namespace))
         (= workload-uid (resource/uid deployment)))))

(defn- ^{:stratum 0} linked? [observed]
  (let [deployment (:kubernetes/deployment observed)
        replica-sets (:kubernetes/replica-sets observed)
        by-uid (into {} (map (juxt resource/uid clojure.core/identity) replica-sets))
        pods (get-in observed [:kubernetes/pods "items"])]
    (and (every? (partial ownership/owns? deployment) replica-sets)
         (every? (fn [pod]
                   (ownership/owns? (get by-uid (get (ownership/controller pod) "uid")) pod)) pods))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [observed]
  (let [captured (resources observed)]
    (and (same-target? observed)
         (unique-identities? resource/uid captured)
         (unique-identities? identity/logical-identity captured)
         (linked? observed))))

(comment ::captured-deployment-membership-not-template-or-runtime-health)
