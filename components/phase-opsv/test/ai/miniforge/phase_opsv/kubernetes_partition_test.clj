;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-partition-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-partition-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} proven-membership-retains-the-entire-raw-inventory-test
  (let [raw (fixtures/with-outside-resources)
        result (phase/partition-kubernetes-deployment-inventory raw)
        members (:kubernetes/membership result)]
    (is (m/validate phase/KubernetesDeploymentInventoryPartition result))
    (is (identical? raw (:kubernetes/inventory result)))
    (is (= ["old-replica-set-uid" "new-replica-set-uid"]
           (mapv #(get-in % ["metadata" "uid"]) (:kubernetes/replica-sets members))))
    (is (= ["old-pod-uid" "new-pod-uid"]
           (mapv #(get-in % ["metadata" "uid"]) (get-in members [:kubernetes/pods "items"]))))
    (is (= members (phase/admit-kubernetes-deployment-membership members)))
    (is (= 3 (count (get-in result [:kubernetes/inventory :kubernetes/pod-inventory "items"]))))))

(deftest ^{:stratum 0} list-context-restores-semantic-types-without-altering-raw-items-test
  (let [raw (fixtures/without-item-types)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (not (anomaly/anomaly? result)))
    (is (identical? raw (:kubernetes/inventory result)))
    (is (= "apps/v1" (get-in result [:kubernetes/membership :kubernetes/replica-sets 0 "apiVersion"])))
    (is (= 2 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"]))))))

(deftest ^{:stratum 0} ambiguous-contradictory-and-unresolved-controller-links-fail-test
  (let [raw (fixtures/with-outside-resources)]
    (doseq [changed [(assoc-in raw [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences" 0 "uid"] "missing-rs")
                     (assoc-in raw [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences" 0 "name"] "wrong")
                     (assoc-in raw [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences" 0 "kind"] "Job")
                     (assoc-in raw [:kubernetes/replica-set-inventory "items" 0 "metadata" "ownerReferences" 0 "kind"] "Job")
                     (update-in raw [:kubernetes/pod-inventory "items" 2 "metadata" "ownerReferences"] #(conj % (first %)))
                     (assoc-in raw [:kubernetes/pod-inventory "items" 0 "metadata" "ownerReferences"]
                               (get-in raw [:kubernetes/replica-set-inventory "items" 0 "metadata" "ownerReferences"]))]]
      (is (= :conflict (:anomaly/type (phase/partition-kubernetes-deployment-inventory changed)))))))

(deftest ^{:stratum 0} malformed-incomplete-foreign-and-duplicate-inventories-fail-test
  (doseq [raw [nil {} (assoc (fixtures/observation) :unexpected true)]]
    (is (= :invalid-input (:anomaly/type (phase/partition-kubernetes-deployment-inventory raw)))))
  (let [raw (fixtures/observation)]
    (doseq [changed [(assoc-in raw [:kubernetes/pod-inventory "metadata" "continue"] "more")
                     (assoc-in raw [:kubernetes/replica-set-inventory "metadata" "remainingItemCount"] 1)
                     (assoc-in raw [:kubernetes/pod-inventory "items" 0 "metadata" "namespace"] "foreign")
                     (update-in raw [:kubernetes/pod-inventory "items"] #(conj % (first %)))
                     (assoc-in raw [:runtime/target :workload-uid] "other-incarnation")
                     (assoc-in raw [:runtime/target :namespace-uid] "other-namespace")
                     (assoc-in raw [:runtime/target :cluster] "other-cluster")
                     (assoc-in raw [:runtime/target :namespace] "other-namespace")]]
      (is (= :conflict (:anomaly/type (phase/partition-kubernetes-deployment-inventory changed)))))))

(deftest ^{:stratum 0} labels-pending-unmanaged-and-zero-do-not-become-health-or-membership-proof-test
  (let [raw (fixtures/pending-member-with-matching-outside-labels)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (= 2 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"]))))
    (is (identical? raw (:kubernetes/inventory result)))
    (is (not (contains? result :opsv/verification-result))))
  (let [raw (fixtures/unmanaged-first :kubernetes/pod-inventory)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (= 1 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"])))))
  (let [raw (fixtures/unmanaged-first :kubernetes/replica-set-inventory)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (identical? raw (:kubernetes/inventory result)))
    (is (= 1 (count (get-in result [:kubernetes/membership :kubernetes/replica-sets]))))
    (is (= 1 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"])))))
  (let [raw (fixtures/empty-inventory)
        result (phase/partition-kubernetes-deployment-inventory raw)]
    (is (m/validate phase/KubernetesDeploymentInventoryPartition result))
    (is (empty? (get-in result [:kubernetes/membership :kubernetes/replica-sets])))
    (is (empty? (get-in result [:kubernetes/membership :kubernetes/pods "items"])))))

(deftest ^{:stratum 0} captured-namespace-owners-retain-exact-identity-without-a-parent-namespace-test
  (doseq [collection [:kubernetes/replica-set-inventory :kubernetes/pod-inventory]]
    (let [raw (fixtures/with-namespace-owner collection)
          result (phase/partition-kubernetes-deployment-inventory raw)]
      (is (not (anomaly/anomaly? result)))
      (is (identical? raw (:kubernetes/inventory result)))
      (is (= 1 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"])))))
    (doseq [[field changed] [["name" "wrong-namespace"] ["kind" "Deployment"] ["apiVersion" "apps/v1"]]]
      (let [raw (assoc-in (fixtures/with-namespace-owner collection)
                          [collection "items" 0 "metadata" "ownerReferences" 0 field] changed)]
        (is (= :conflict (:anomaly/type (phase/partition-kubernetes-deployment-inventory raw))))))))

(deftest ^{:stratum 0} unresolved-native-replica-set-owners-cannot-hide-behind-another-version-test
  (let [original (fixtures/observation)
        captured-uid (get-in original [:kubernetes/replica-set-inventory "items" 0 "metadata" "uid"])]
    (doseq [version ["apps/v1beta2" "extensions/v1beta1" "apps/v2"]
            uid [captured-uid "missing-native-parent"]]
      (let [raw (fixtures/with-pod-controller version uid)]
        (is (= :conflict (:anomaly/type (phase/partition-kubernetes-deployment-inventory raw))))))
    (let [raw (fixtures/with-pod-controller "example.invalid/v1" "uncaptured-custom-parent")
          result (phase/partition-kubernetes-deployment-inventory raw)]
      (is (not (anomaly/anomaly? result)))
      (is (identical? raw (:kubernetes/inventory result)))
      (is (= 1 (count (get-in result [:kubernetes/membership :kubernetes/pods "items"])))))))

(comment ::outside-observations-remain-drift-sensitive-not-silently-filtered)
