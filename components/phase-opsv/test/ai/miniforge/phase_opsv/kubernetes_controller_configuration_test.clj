;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-configuration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-outside-fixtures :as outside]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} raw-counts-and-versioned-controller-configuration-remain-separate-test
  (let [input (fixtures/input)
        result (phase/project-kubernetes-controller-configuration input)
        projection (:kubernetes/controller-configuration result)]
    (is (not (anomaly/anomaly? result)))
    (is (identical? input (:kubernetes/source result)))
    (is (= {:controller/deployment-replicas 2 :controller/replica-set-total 2
            :controller/replica-set-replicas {"old-replica-set-uid" 1 "new-replica-set-uid" 1}}
           (:kubernetes/controller-counts result)))
    (is (= :kubernetes/controller-desired-configuration-v1 (:kubernetes/projection projection)))
    (is (= 3 (count (:kubernetes/controllers projection))))
    (is (= (:kubernetes/controller-configuration-hash result) (artifact/content-digest projection)))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} bounded-count-changes-preserve-desired-configuration-without-erasing-count-observations-test
  (let [input (fixtures/input)
        changed (-> input
                    (assoc-in [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] 3)
                    (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0 "spec" "replicas"] 2))
        baseline (phase/project-kubernetes-controller-configuration input)
        observed (phase/project-kubernetes-controller-configuration changed)]
    (is (= (:kubernetes/controller-configuration-hash baseline) (:kubernetes/controller-configuration-hash observed)))
    (is (= 3 (get-in observed [:kubernetes/controller-counts :controller/deployment-replicas])))
    (is (= 3 (get-in observed [:kubernetes/controller-counts :controller/replica-set-total])))
    (is (identical? changed (:kubernetes/source observed)))))

(deftest ^{:stratum 0} deployment-and-total-owned-replica-set-counts-must-each-stay-within-bounds-test
  (let [input (fixtures/input)]
    (doseq [changed [(assoc-in input [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] 4)
                     (assoc-in input [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] 0)
                     (assoc-in input [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0 "spec" "replicas"] 3)
                     (-> input
                         (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0 "spec" "replicas"] 0)
                         (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 1 "spec" "replicas"] 0))]]
      (is (= :conflict (:anomaly/type (phase/project-kubernetes-controller-configuration changed)))))))

(deftest ^{:stratum 0} zero-is-explicit-and-missing-or-malformed-native-counts-never-default-to-it-test
  (let [input (fixtures/input)
        zero (-> input
                 (assoc :comparison/bounds {:replicas/min-count 0 :replicas/max-count 0})
                 (assoc-in [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] 0)
                 (assoc-in [:kubernetes/inventory :kubernetes/replica-set-inventory "items"] [])
                 (assoc-in [:kubernetes/inventory :kubernetes/pod-inventory "items"] []))
        result (phase/project-kubernetes-controller-configuration zero)]
    (is (= 0 (get-in result [:kubernetes/controller-counts :controller/deployment-replicas])))
    (is (= 0 (get-in result [:kubernetes/controller-counts :controller/replica-set-total])))
    (is (= {} (get-in result [:kubernetes/controller-counts :controller/replica-set-replicas])))
    (doseq [value [nil -1 1.5 (inc (long Integer/MAX_VALUE))]]
      (is (= :invalid-input
             (:anomaly/type (phase/project-kubernetes-controller-configuration
                              (assoc-in zero [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"] value))))))
    (is (= :invalid-input
           (:anomaly/type (phase/project-kubernetes-controller-configuration
                            (update-in zero [:kubernetes/inventory :kubernetes/deployment "spec"] dissoc "replicas")))))))

(deftest ^{:stratum 0} malformed-bounds-and-foreign-controller-membership-fail-closed-test
  (let [input (fixtures/input)]
    (doseq [changed [nil {} (dissoc input :comparison/bounds)
                     (assoc input :comparison/bounds {:replicas/min-count 4 :replicas/max-count 3})
                     (assoc-in input [:comparison/bounds :unrecognized] true)
                     (assoc-in input [:kubernetes/inventory :runtime/target :workload-uid] "foreign")]]
      (is (anomaly/anomaly? (phase/project-kubernetes-controller-configuration changed))))))

(deftest ^{:stratum 0} every-owned-replica-set-needs-a-valid-explicit-native-count-test
  (let [input (fixtures/input)
        path [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0 "spec"]]
    (doseq [changed [(assoc-in input (conj path "replicas") nil)
                     (assoc-in input (conj path "replicas") -1)
                     (update-in input path dissoc "replicas")]]
      (is (= :invalid-input (:anomaly/type (phase/project-kubernetes-controller-configuration changed)))))))

(deftest ^{:stratum 0} previously-owned-controller-leaving-the-target-remains-visible-on-both-sides-test
  (let [input (fixtures/input)
        changed (assoc-in input [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 0
                                  "metadata" "ownerReferences" 0 "uid"] "former-deployment")
        baseline (phase/project-kubernetes-controller-configuration input)
        observed (phase/project-kubernetes-controller-configuration changed)
        before (assoc (outside/input) :kubernetes/inventory (:kubernetes/inventory input))
        after (assoc before :kubernetes/inventory (:kubernetes/inventory changed))]
    (is (not (anomaly/anomaly? observed)))
    (is (not= (:kubernetes/controller-configuration-hash baseline) (:kubernetes/controller-configuration-hash observed)))
    (is (not= (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants before))
              (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants after))))))

(deftest ^{:stratum 0} native-count-normalization-never-erases-other-owned-desired-fields-test
  (let [input (fixtures/input)
        baseline (:kubernetes/controller-configuration-hash (phase/project-kubernetes-controller-configuration input))
        deployment [:kubernetes/inventory :kubernetes/deployment]]
    (doseq [[path value] [[(into deployment ["spec" "template" "spec" "containers" 0 "image"]) "registry.invalid/other:version"]
                          [(into deployment ["spec" "strategy"]) {"type" "Recreate"}]
                          [(into deployment ["metadata" "annotations"]) {"example.invalid/owner" "other"}]]]
      (let [result (phase/project-kubernetes-controller-configuration (assoc-in input path value))]
        (is (not (anomaly/anomaly? result)))
        (is (not= baseline (:kubernetes/controller-configuration-hash result)))))))

(deftest ^{:stratum 0} owned-controller-ownership-and-lifecycle-remain-configuration-test
  (let [input (fixtures/input)
        baseline (:kubernetes/controller-configuration-hash (phase/project-kubernetes-controller-configuration input))]
    (doseq [[field value] [["ownerReferences" [(inventory/non-controller-rbac-reference)]]
                           ["finalizers" ["example.invalid/hold"]]
                           ["deletionTimestamp" "2026-11-04T00:00:00Z"]]]
      (let [changed (assoc-in input [:kubernetes/inventory :kubernetes/deployment "metadata" field] value)
            observed (phase/project-kubernetes-controller-configuration changed)]
        (is (not (anomaly/anomaly? observed)))
        (is (not= baseline (:kubernetes/controller-configuration-hash observed)))))))

(deftest ^{:stratum 0} unowned-controller-counts-remain-outside-rather-than-normalized-test
  (let [input (fixtures/input)
        changed (assoc-in input [:kubernetes/inventory :kubernetes/replica-set-inventory "items" 2 "spec" "replicas"] 999)
        baseline (phase/project-kubernetes-controller-configuration input)
        observed (phase/project-kubernetes-controller-configuration changed)
        before (assoc (outside/input) :kubernetes/inventory (:kubernetes/inventory input))
        after (assoc before :kubernetes/inventory (:kubernetes/inventory changed))]
    (is (= (:kubernetes/controller-configuration-hash baseline) (:kubernetes/controller-configuration-hash observed)))
    (is (not= (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants before))
              (:kubernetes/outside-hash (phase/project-kubernetes-outside-invariants after))))))
