;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} complete-inventory-binds-namespace-and-deployment-before-listing-test
  (let [[result calls] (fixtures/capture! (fixtures/routes) {})]
    (is (not (anomaly/anomaly? result)))
    (is (= (:runtime/target (fixtures/declaration)) (:runtime/target result)))
    (is (= [fixtures/namespace-path fixtures/deployment-path fixtures/replica-sets-path fixtures/pods-path
             fixtures/namespace-path fixtures/deployment-path]
           (mapv wire/path calls)))
    (is (every? #(= :get (:method %)) calls))
    (is (= {:limit 998} (:query-params (nth calls 2))))
    (is (= {:limit 996} (:query-params (nth calls 3))))
    (is (= 2 (count (get-in result [:kubernetes/replica-set-inventory "items"]))))
    (is (= 2 (count (get-in result [:kubernetes/pod-inventory "items"]))))))

(deftest ^{:stratum 0} explicit-inventory-settings-cannot-narrow-or-redirect-the-scope-test
  (doseq [settings [{:kubernetes/pod-selector "app=one-replica"}
                    {:kubernetes/node-pool-label "ignored-setting"}
                    {:kubernetes/namespace "other"}
                    {:kubernetes/cluster-id "other"}
                    {:kubernetes/base-url "http://cluster.invalid"}]]
    (let [[result calls] (fixtures/capture! (fixtures/routes) settings)]
      (is (anomaly/anomaly? result))
      (is (empty? calls))))
  (doseq [declared [nil {} []
                    (assoc (fixtures/declaration) :unexpected true)
                    (assoc-in (fixtures/declaration) [:runtime/target :namespace-uid] nil)
                    (assoc (fixtures/declaration) :kubernetes/deployment-name "../secrets")]]
    (let [[result calls] (fixtures/observe! (fixtures/routes) {} declared)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (empty? calls)))))

(deftest ^{:stratum 0} recreated-root-identities-stop-before-namespace-inventory-reads-test
  (doseq [[path expected-calls] [[[fixtures/namespace-path "metadata" "uid"] 1]
                                [[fixtures/deployment-path "metadata" "uid"] 2]]]
    (let [[result calls] (fixtures/capture! (assoc-in (fixtures/routes) path "recreated-uid") {})]
      (is (= :conflict (:anomaly/type result)))
      (is (= expected-calls (count calls))))))

(deftest ^{:stratum 0} total-budget-is-reserved-before-each-list-and-never-becomes-limit-zero-test
  (doseq [[budget expected-calls] [[1 0] [2 0] [4 3] [5 4]]]
    (let [[result calls] (fixtures/capture! (fixtures/routes) {:kubernetes/max-resources budget})]
      (is (anomaly/anomaly? result))
      (is (= expected-calls (count calls)))
      (is (every? #(pos? (get-in % [:query-params :limit] 1)) calls))))
  (let [[result calls] (fixtures/capture! (fixtures/routes) {:kubernetes/max-resources 6})]
    (is (not (anomaly/anomaly? result)))
    (is (= [4 2] (mapv #(get-in % [:query-params :limit]) (take 2 (drop 2 calls)))))))

(deftest ^{:stratum 0} incomplete-and-foreign-lists-stop-before-further-reads-test
  (doseq [[path expected-calls] [[fixtures/replica-sets-path 3] [fixtures/pods-path 4]]
          [field value] [["continue" "more"] ["remainingItemCount" 1] ["remainingItemCount" -1]
                         ["resourceVersion" ""]]]
    (let [routes (assoc-in (fixtures/routes) [path "metadata" field] value)
          [result calls] (fixtures/capture! routes {})]
      (is (anomaly/anomaly? result))
      (is (= expected-calls (count calls)))))
  (doseq [path [fixtures/replica-sets-path fixtures/pods-path]]
    (let [routes (assoc-in (fixtures/routes) [path "items" 0 "metadata" "namespace"] "foreign")
          [result] (fixtures/capture! routes {})]
      (is (anomaly/anomaly? result)))))

(deftest ^{:stratum 0} namespace-inventory-retains-unrelated-and-pending-pods-test
  (let [routes (fixtures/routes)
        pod (-> (get-in routes [fixtures/pods-path "items" 0])
                (assoc-in ["metadata" "name"] "unrelated")
                (assoc-in ["metadata" "uid"] "unrelated-uid")
                (update "metadata" dissoc "ownerReferences")
                (update "spec" dissoc "nodeName")
                (assoc "status" {"phase" "Pending"}))
        [result calls] (fixtures/capture! (update-in routes [fixtures/pods-path "items"] conj pod) {})]
    (is (m/validate phase/KubernetesDeploymentInventoryObservation result))
    (is (= pod (last (get-in result [:kubernetes/pod-inventory "items"]))))
    (is (= 3 (count (get-in result [:kubernetes/pod-inventory "items"]))))
    (is (= 6 (count calls)))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} empty-native-inventories-retain-complete-observation-support-test
  (let [routes (-> (fixtures/routes)
                   (assoc-in [fixtures/replica-sets-path "items"] [])
                   (assoc-in [fixtures/pods-path "items"] []))
        [result calls] (fixtures/capture! routes {:kubernetes/max-resources 3})]
    (is (m/validate phase/KubernetesDeploymentInventoryObservation result))
    (is (= [1 1] (mapv #(get-in % [:query-params :limit]) (take 2 (drop 2 calls)))))))

(deftest ^{:stratum 0} duplicate-uids-and-logical-names-cannot-become-a-consistent-inventory-test
  (let [routes (fixtures/routes)
        pod (get-in routes [fixtures/pods-path "items" 0])]
    (doseq [duplicate [pod (assoc-in pod ["metadata" "uid"] "same-logical-name-new-uid")]]
      (let [raw (-> pod (dissoc "apiVersion" "kind"))
            routes (-> routes
                       (assoc-in [fixtures/pods-path "items" 0] raw)
                       (update-in [fixtures/pods-path "items"] conj duplicate))
            [result] (fixtures/capture! routes {})]
        (is (= :conflict (:anomaly/type result)))))))

(comment ::inventory-is-not-a-membership-or-verification-verdict)
