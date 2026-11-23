;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-workload-inventory
  (:require [ai.miniforge.phase-opsv.kubernetes-identity-consistency :as identity]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} resources [inventory]
  (concat [(:kubernetes/namespace inventory) (:kubernetes/deployment inventory)]
          (get-in inventory [:kubernetes/replica-set-inventory "items"])
          (get-in inventory [:kubernetes/pod-inventory "items"])))

(defn- ^{:stratum 0} logical-key [type value]
  (into type [(resource/namespace-of value) (resource/name-of value)]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unique-identities? [inventory]
  (let [roots [(:kubernetes/namespace inventory) (:kubernetes/deployment inventory)]
        replica-sets (get-in inventory [:kubernetes/replica-set-inventory "items"])
        pods (get-in inventory [:kubernetes/pod-inventory "items"])
        uids (map resource/uid (resources inventory))
        logical (concat (map identity/logical-identity roots)
                        (map (partial logical-key ["apps/v1" "ReplicaSet"]) replica-sets)
                        (map (partial logical-key ["v1" "Pod"]) pods))]
    (and (= (count uids) (count (distinct uids)))
         (= (count logical) (count (distinct logical))))))

(comment ::list-context-provides-typemeta-without-altering-raw-observations)
