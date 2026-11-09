;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-binding
  "Correlate a committing proposal with the sole native request that may be sent."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-effect-binding :as native]
            [ai.miniforge.phase-opsv.kubernetes-replica-payload :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reference-bound? [binding]
  (let [target (:runtime/target binding)
        references (:opsv/resources binding)
        expected ["apps/v1" "Deployment" (:namespace target)
                  (:kubernetes/deployment-name binding) (:workload-uid target)]]
    (and (= 1 (count references))
         (= expected (mapv (first references) [:api-version :kind :namespace :name :uid])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matches? [settings record]
  (let [proposal (:effect/proposal record)
        binding (:opsv/experiment proposal)
        payload (native/request record)
        [_ revision count] (mapv #(get % "value") (:body payload))
        expected (wire/request (native/reference record) revision count)
        target (:runtime/target binding)]
    (and (= (:effect/id record) (:effect/id proposal))
         (= (:kubernetes/cluster-id settings) (:cluster target))
         (= (:kubernetes/namespace settings) (:namespace target))
         (= (:kubernetes/context settings) (:kubernetes/context binding))
         (reference-bound? binding)
         (= expected payload)
         (= (hash/content-hash payload) (:opsv/payload-hash binding)))))

(comment ::exact-request-data-not-admission-or-ownership-proof)
