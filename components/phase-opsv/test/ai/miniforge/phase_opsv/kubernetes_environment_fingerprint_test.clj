;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-replica-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures :as controllers]
            [malli.core :as m]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} canonical-values-bind-original-semantic-projection-without-execution-verdict-test
  (let [{:keys [capture bounds]} (fixtures/input)
        result (phase/project-kubernetes-environment-fingerprint capture bounds)
        fingerprint (:environment-fingerprint result)]
    (is (not (anomaly/anomaly? result)))
    (is (m/validate schema/Fingerprint fingerprint))
    (is (identical? capture (:kubernetes/source result)))
    (is (= (get-in capture [:kubernetes/inventory :runtime/target]) (:runtime/target result)))
    (is (= "staging-cluster" (:cluster fingerprint)))
    (is (= :kubernetes/environment-configuration-v1
           (get-in result [:kubernetes/environment-projection :kubernetes/projection])))
    (is (= (:config-hash fingerprint) (artifact/content-digest (:kubernetes/environment-projection result))))
    (is (= 2 (count (:comparison/replica-observations result))))
    (is (= 2 (get-in result [:kubernetes/controller-counts :controller/deployment-replicas])))
    (is (= bounds (:comparison/bounds result)))
    (is (= 2 (count (:kubernetes/replica-configurations result))))
    (is (= 2 (count (:kubernetes/pod-lifecycles result))))
    (is (= :kubernetes/outside-workload-invariants-v1
           (get-in result [:kubernetes/outside-projection :kubernetes/projection])))
    (is (= :kubernetes/controller-desired-configuration-v1
           (get-in result [:kubernetes/controller-configuration :kubernetes/projection])))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} bounded-replacement-churn-retains-fingerprint-but-not-original-instance-identities-test
  (let [{:keys [capture bounds]} (fixtures/input)
        changed (replicas/replacement capture)
        baseline (phase/project-kubernetes-environment-fingerprint capture bounds)
        observed (phase/project-kubernetes-environment-fingerprint changed bounds)]
    (is (not (anomaly/anomaly? observed)))
    (is (= (:environment-fingerprint baseline) (:environment-fingerprint observed)))
    (is (not= (:comparison/replica-observations baseline) (:comparison/replica-observations observed)))))

(deftest ^{:stratum 0} unrelated-and-owned-desired-changes-remain-drift-sensitive-test
  (let [{:keys [capture bounds]} (fixtures/input)
        baseline (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint capture bounds))]
    (doseq [[path value] [[[:kubernetes/configuration 0 "data" "setting"] "other"]
                          [[:kubernetes/inventory :kubernetes/deployment "spec" "strategy"] {"type" "Recreate"}]
                          [[:kubernetes/nodes "items" 0 "spec" "unschedulable"] true]]]
      (let [observed (phase/project-kubernetes-environment-fingerprint (assoc-in capture path value) bounds)]
        (is (not (anomaly/anomaly? observed)))
        (is (not= baseline (:environment-fingerprint observed)))))))

(deftest ^{:stratum 0} missing-malformed-empty-and-inconsistent-observations-never-mint-a-fingerprint-test
  (let [{:keys [capture bounds]} (fixtures/input)]
    (doseq [changed [nil {} (dissoc capture :kubernetes/nodes)
                     (assoc-in capture [:kubernetes/inventory :kubernetes/pod-inventory "items"] [])
                     (assoc-in capture [:kubernetes/inventory :kubernetes/pod-inventory "items" 0 "status"] {})]]
      (is (anomaly/anomaly? (phase/project-kubernetes-environment-fingerprint changed bounds))))
    (doseq [invalid [nil {} (fixtures/bounds 4 3) (fixtures/bounds 3 3)]]
      (is (anomaly/anomaly? (phase/project-kubernetes-environment-fingerprint capture invalid))))))

(deftest ^{:stratum 0} controller-count-normalization-retains-count-observations-test
  (let [{:keys [capture bounds]} (fixtures/input)
        changed (controllers/with-desired-counts capture 3 2 1)
        baseline (phase/project-kubernetes-environment-fingerprint capture bounds)
        observed (phase/project-kubernetes-environment-fingerprint changed bounds)]
    (is (not (anomaly/anomaly? observed)))
    (is (= (:environment-fingerprint baseline) (:environment-fingerprint observed)))
    (is (= 3 (get-in observed [:kubernetes/controller-counts :controller/deployment-replicas])))))

(deftest ^{:stratum 0} heterogeneous-runtime-configuration-or-images-never-select-first-replica-test
  (let [{:keys [capture bounds]} (fixtures/input)
        pod [:kubernetes/inventory :kubernetes/pod-inventory "items" 0]
        other-image (fixtures/other-image)]
    (doseq [[path value] [[(into pod ["spec" "containers" 0 "env"]) (fixtures/environment-setting)]
                          [(into pod ["status" "containerStatuses" 0 "imageID"]) other-image]]]
      (is (= :conflict (:anomaly/type
                        (phase/project-kubernetes-environment-fingerprint (assoc-in capture path value) bounds)))))))

(deftest ^{:stratum 0} native-node-pool-inventory-not-current-placement-defines-canonical-pools-test
  (let [{:keys [capture bounds]} (fixtures/input)
        input (fixtures/with-pool capture "pool-b")
        observed (phase/project-kubernetes-environment-fingerprint input bounds)]
    (is (not (anomaly/anomaly? observed)))
    (is (= ["pool-a" "pool-b"] (get-in observed [:environment-fingerprint :node-pools])))))

(deftest ^{:stratum 0} homogeneous-runtime-template-and-image-changes-remain-observable-test
  (let [{:keys [capture bounds]} (fixtures/input)
        baseline (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint capture bounds))]
    (doseq [[path value] [[["spec" "containers" 0 "env"] (fixtures/environment-setting)]
                          [["status" "containerStatuses" 0 "imageID"] (fixtures/other-image)]]]
      (let [changed (fixtures/with-member-field capture path value)
            result (phase/project-kubernetes-environment-fingerprint changed bounds)]
        (is (not (anomaly/anomaly? result)))
        (is (not= baseline (:environment-fingerprint result)))))))

(deftest ^{:stratum 0} complete-capture-resource-order-is-not-environment-drift-test
  (let [{:keys [capture bounds]} (fixtures/input)
        changed (-> capture
                    (update :kubernetes/configuration (comp vec reverse))
                    (update-in [:kubernetes/nodes "items"] (comp vec reverse))
                    (update-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] (comp vec reverse))
                    (update-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] (comp vec reverse)))]
    (is (= (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint capture bounds))
           (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint changed bounds))))))

(deftest ^{:stratum 0} selected-pod-lifecycle-and-controller-role-changes-remain-drift-sensitive-test
  (let [{:keys [capture bounds]} (fixtures/input)
        baseline (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint capture bounds))]
    (doseq [[path value] [[["metadata" "finalizers"] ["example.invalid/hold"]]
                          [["metadata" "deletionTimestamp"] "2026-11-04T00:00:00Z"]
                          [["metadata" "ownerReferences" 0 "blockOwnerDeletion"] false]]]
      (let [changed (fixtures/with-member-field capture path value)
            observed (phase/project-kubernetes-environment-fingerprint changed bounds)]
        (is (not (anomaly/anomaly? observed)))
        (is (not= baseline (:environment-fingerprint observed)))))))

(deftest ^{:stratum 0} selected-pod-metadata-is-admitted-before-lifecycle-canonicalization-test
  (let [{:keys [capture bounds]} (fixtures/input)]
    (doseq [value [42 ["hold" "hold"]]]
      (let [changed (fixtures/with-member-field capture ["metadata" "finalizers"] value)]
        (is (= :conflict (:anomaly/type (phase/project-kubernetes-environment-fingerprint changed bounds))))))))

(deftest ^{:stratum 0} selected-pod-metadata-order-is-not-drift-but-noncontroller-ownership-is-test
  (let [{:keys [capture bounds]} (fixtures/input)
        input (fixtures/with-lifecycle-metadata capture)
        reordered (fixtures/reverse-lifecycle-metadata input)
        changed (fixtures/with-member-field input ["metadata" "ownerReferences" 1 "uid"] "other-rbac-owner")
        baseline (phase/project-kubernetes-environment-fingerprint input bounds)]
    (is (not (anomaly/anomaly? baseline)))
    (is (= (:environment-fingerprint baseline)
           (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint reordered bounds))))
    (is (not= (:environment-fingerprint baseline)
              (:environment-fingerprint (phase/project-kubernetes-environment-fingerprint changed bounds))))))
