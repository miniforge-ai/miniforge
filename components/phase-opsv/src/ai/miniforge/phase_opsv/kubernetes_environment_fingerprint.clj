;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-environment-fingerprint
  "Canonical observed values, separate from population admission and execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pools [capture]
  (let [label (:kubernetes/node-pool-label capture)]
    (->> (get-in capture [:kubernetes/nodes "items"])
         (keep #(get-in % ["metadata" "labels" label]))
         (remove str/blank?) distinct sort vec)))

(defn- ^{:stratum 0} runtime [replicas]
  (let [observations (:comparison/replica-observations replicas)
        configurations (distinct (map :replica/template-hash observations))
        images (distinct (map :replica/image-digests observations))]
    ;; Never substitute declared images for absent or heterogeneous runtime evidence.
    (when (and (= 1 (count configurations)) (= 1 (count images)) (seq (first images)))
      {:kubernetes/template-hash (first configurations)
       :image-digests (first images)})))

(defn- ^{:stratum 0} configuration [outside controllers observed lifecycles]
  {:kubernetes/projection :kubernetes/environment-configuration-v1
   :kubernetes/outside-hash (:kubernetes/outside-hash outside)
   :kubernetes/controller-configuration-hash (:kubernetes/controller-configuration-hash controllers)
   :kubernetes/template-hash (:kubernetes/template-hash observed)
   :kubernetes/pod-lifecycle-hashes (:kubernetes/pod-lifecycle-hashes lifecycles)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} canonical-values [capture observed digest]
  {:cluster (:kubernetes/cluster-id (:kubernetes/inventory capture))
   :node-pools (pools capture)
   :image-digests (:image-digests observed)
   :config-hash digest})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [capture outside controllers replicas lifecycles]
  (when-let [observed (runtime replicas)]
    (let [projection (configuration outside controllers observed lifecycles)]
      (anomaly/let-ok [digest (artifact/content-digest projection)]
        {:kubernetes/source capture
         :runtime/target (get-in capture [:kubernetes/inventory :runtime/target])
         :environment-fingerprint (canonical-values capture observed digest)
         :kubernetes/environment-projection projection
         :kubernetes/outside-projection (:kubernetes/outside-projection outside)
         :kubernetes/controller-configuration (:kubernetes/controller-configuration controllers)
         :kubernetes/replica-configurations (:kubernetes/replica-configurations replicas)
         :kubernetes/pod-lifecycles (:kubernetes/pod-lifecycles lifecycles)
         :comparison/bounds (get-in controllers [:kubernetes/source :comparison/bounds])
         :kubernetes/controller-counts (:kubernetes/controller-counts controllers)
         :comparison/replica-observations (:comparison/replica-observations replicas)}))))

(comment ::observe-before-comparison-and-protect-before-reporting)
