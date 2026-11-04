;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-observations-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} captured-members-produce-correlated-runtime-and-versioned-configuration-test
  (let [input (fixtures/input)
        result (phase/project-kubernetes-deployment-replicas input)
        observations (:comparison/replica-observations result)]
    (is (not (anomaly/anomaly? result)))
    (is (identical? input (:kubernetes/source result)))
    (is (= 3 (count (get-in result [:kubernetes/source :kubernetes/inventory :kubernetes/pod-inventory "items"]))))
    (is (= 2 (count observations)))
    (doseq [observed observations]
      (let [configuration (get-in result [:kubernetes/replica-configurations (:replica/id observed)])]
        (is (m/validate replicas/Observation observed))
        (is (= "pool-a" (:replica/node-pool observed)))
        (is (= {:kubernetes/projection :kubernetes/deployment-replica-configuration-v1}
               (select-keys configuration [:kubernetes/projection])))
        (is (= (:replica/template-hash observed) (artifact/content-digest configuration)))
        (is (= {"regular/service" (str "sha256:" (apply str (repeat 64 "a")))}
               (:replica/image-digests observed)))))))

(deftest ^{:stratum 0} replacement-uid-generated-binding-and-placement-do-not-fabricate-configuration-drift-test
  (let [input (fixtures/input)
        baseline (phase/project-kubernetes-deployment-replicas input)
        replacement (phase/project-kubernetes-deployment-replicas (fixtures/replacement input))
        request (fixtures/request baseline replacement)]
    (is (not= (mapv :replica/id (:comparison/replica-observations baseline))
              (mapv :replica/id (:comparison/replica-observations replacement))))
    (is (= (:comparison/observed request) (phase/compare-verification-environment request)))
    (is (= (get-in input [:kubernetes/inventory :kubernetes/pod-inventory "items" 2])
           (get-in replacement [:kubernetes/source :kubernetes/inventory :kubernetes/pod-inventory "items" 2])))))

(deftest ^{:stratum 0} native-configuration-runtime-image-and-node-pool-differences-remain-visible-test
  (let [input (fixtures/input)
        baseline (phase/project-kubernetes-deployment-replicas input)
        pod-path [:kubernetes/inventory :kubernetes/pod-inventory "items" 0]]
    (doseq [changed [(assoc-in input (into pod-path ["spec" "containers" 0 "image"]) "registry.invalid/service:other")
                     (assoc-in input (into pod-path ["status" "containerStatuses" 0 "imageID"])
                               (str "sha256:" (apply str (repeat 64 "b"))))
                     (assoc-in input [:kubernetes/nodes "items" 0 "metadata" "labels" kube/pool-label] "foreign")]]
      (let [observed (phase/project-kubernetes-deployment-replicas changed)]
        (is (not (anomaly/anomaly? observed)))
        (is (= :conflict (:anomaly/type (phase/compare-verification-environment
                                         (fixtures/request baseline observed)))))))))

(deftest ^{:stratum 0} metadata-resources-and-token-contents-remain-configuration-test
  (let [input (fixtures/input)
        baseline (phase/project-kubernetes-deployment-replicas input)
        pod-path [:kubernetes/inventory :kubernetes/pod-inventory "items" 0]]
    (doseq [changed [(assoc-in input (into pod-path ["metadata" "annotations"]) {"example.invalid/owner" "other"})
                     (assoc-in input (into pod-path ["spec" "containers" 0 "resources"]) {"limits" {"cpu" "1"}})
                     (assoc-in input (into pod-path ["spec" "volumes" 0 "projected" "sources" 0
                                                    "serviceAccountToken" "expirationSeconds"]) 1)]]
      (let [observed (phase/project-kubernetes-deployment-replicas changed)]
        (is (not (anomaly/anomaly? observed)))
        (is (= :conflict (:anomaly/type (phase/compare-verification-environment
                                         (fixtures/request baseline observed)))))))))

(deftest ^{:stratum 0} incomplete-ambiguous-or-unresolved-native-observations-fail-closed-test
  (let [input (fixtures/input)
        pod-path [:kubernetes/inventory :kubernetes/pod-inventory "items" 0]]
    (doseq [changed [nil {}
                     (assoc-in input [:kubernetes/nodes "metadata" "continue"] "next-page")
                     (assoc-in input [:kubernetes/inventory :kubernetes/pod-inventory "metadata" "continue"] "next-page")
                     (assoc-in input [:kubernetes/nodes "items"] [])
                     (update-in input [:kubernetes/nodes "items"] #(conj % (first %)))
                     (assoc-in input [:kubernetes/nodes "items" 0 "metadata" "uid"] "old-pod-uid")
                     (assoc-in input [:kubernetes/nodes "items" 0 "metadata" "labels" kube/pool-label] " ")
                     (update-in input (into pod-path ["spec"]) dissoc "nodeName")
                     (assoc-in input (into pod-path ["status" "containerStatuses"]) [])
                     (assoc-in input (into pod-path ["status" "containerStatuses" 0 "name"]) "foreign")
                     (assoc-in input (into pod-path ["status" "containerStatuses" 0 "imageID"]) "registry.invalid/service:mutable")
                     (assoc-in input (into pod-path ["spec" "containers" 0 "volumeMounts" 0 "name"]) "unresolved-volume")
                     (assoc-in input (into pod-path ["metadata" "ownerReferences" 0 "uid"]) "foreign")]]
      (is (anomaly/anomaly? (phase/project-kubernetes-deployment-replicas changed))))))

(deftest ^{:stratum 0} empty-populations-are-explicit-not-a-missing-observation-test
  (let [input (-> (fixtures/input)
                  (assoc-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] [])
                  (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] [])
                  (assoc-in [:kubernetes/nodes "items"] []))
        result (phase/project-kubernetes-deployment-replicas input)]
    (is (identical? input (:kubernetes/source result)))
    (is (= [] (:comparison/replica-observations result)))
    (is (= {} (:kubernetes/replica-configurations result)))))

(deftest ^{:stratum 0} pool-label-interpretation-is-bound-not-learned-after-observation-test
  (let [alternative "example.invalid/alternative-pool"
        input (update-in (fixtures/input) [:kubernetes/nodes "items"]
                         (partial mapv #(assoc-in % ["metadata" "labels" alternative] "pool-a")))
        baseline (phase/project-kubernetes-deployment-replicas input)
        changed (phase/project-kubernetes-deployment-replicas (assoc input :kubernetes/node-pool-label alternative))]
    (is (not (anomaly/anomaly? changed)))
    (is (= :conflict (:anomaly/type (phase/compare-verification-environment (fixtures/request baseline changed)))))))
