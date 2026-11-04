;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-verification-capture-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} complete-raw-capture-retains-shared-deadline-scope-and-read-only-provenance-test
  (let [deadline io/deadline
        deadlines (atom 0)
        routes (fixtures/routes)
        [result calls] (with-redefs [io/deadline (fn [millis] (swap! deadlines inc) (deadline millis))]
                         (fixtures/capture! routes {}))]
    (is (not (anomaly/anomaly? result)))
    (is (m/validate phase/KubernetesVerificationObservation result))
    (is (= 1 @deadlines))
    (is (= 13 (count calls)))
    (is (every? #(= :get (:method %)) calls))
    (is (not-any? #(contains? (:query-params %) :labelSelector) calls))
    (is (= (get routes fixtures/nodes-path) (:kubernetes/nodes result)))
    (is (= (get routes fixtures/cluster-path) (:kubernetes/cluster result)))
    (is (= (get routes inventory/pods-path) (get-in result [:kubernetes/inventory :kubernetes/pod-inventory])))
    (is (= [(get routes wire/config-path) (get routes wire/secret-path)] (:kubernetes/configuration result)))
    (is (= [inventory/namespace-path inventory/deployment-path fixtures/cluster-path]
           (mapv wire/path (take-last 3 calls))))
    (is (not (contains? result :opsv/verification-result)))))

(deftest ^{:stratum 0} aggregate-object-budget-covers-inventory-nodes-and-deduplicated-dependencies-test
  (let [[result calls] (fixtures/capture! (fixtures/routes) {:kubernetes/max-resources 10})]
    (is (not (anomaly/anomaly? result)))
    (is (= 3 (get-in (nth calls 7) [:query-params :limit]))))
  (doseq [budget [6 7 8 9]]
    (let [[result calls] (fixtures/capture! (fixtures/routes) {:kubernetes/max-resources budget})
          paths (mapv wire/path calls)]
      (is (anomaly/anomaly? result))
      (is (not-any? #{wire/config-path wire/secret-path} paths))
      (is (every? #(pos? (get-in % [:query-params :limit] 1)) calls)))))

(deftest ^{:stratum 0} zero-replicas-still-capture-current-desired-template-dependencies-test
  (let [routes (-> (fixtures/routes)
                   (assoc-in [inventory/pods-path "items"] [])
                   (assoc-in [inventory/replica-sets-path "items"] []))
        [result calls] (fixtures/capture! routes {})]
    (is (not (anomaly/anomaly? result)))
    (is (empty? (get-in result [:kubernetes/inventory :kubernetes/pod-inventory "items"])))
    (is (= 2 (count (:kubernetes/configuration result))))
    (is (= 1 (count (filter #(= wire/config-path (wire/path %)) calls))))))

(deftest ^{:stratum 0} exact-final-object-budget-can-finish-with-no-remaining-dependency-reads-test
  (let [routes (-> (fixtures/routes)
                   (assoc-in [inventory/pods-path "items"] [])
                   (assoc-in [inventory/replica-sets-path "items"] [])
                   (update-in [inventory/deployment-path "spec" "template" "spec" "containers" 0]
                              dissoc "env" "envFrom"))
        [result] (fixtures/capture! routes {:kubernetes/max-resources 4})
        [empty-nodes] (fixtures/capture! (assoc-in routes [fixtures/nodes-path "items"] []) {})]
    (is (m/validate phase/KubernetesVerificationObservation result))
    (is (= [] (:kubernetes/configuration result)))
    (is (m/validate phase/KubernetesVerificationObservation empty-nodes))
    (is (= [] (get-in empty-nodes [:kubernetes/nodes "items"])))))

(deftest ^{:stratum 0} pending-unrelated-pods-and-old-controller-dependencies-are-not-silently-excluded-test
  (let [routes (-> (fixtures/routes)
                   (update-in [inventory/pods-path "items" 0 "spec"] dissoc "nodeName")
                   (assoc-in [inventory/pods-path "items" 0 "status"] {"phase" "Pending"})
                   (update-in [inventory/pods-path "items" 0 "metadata"] dissoc "ownerReferences")
                   (assoc-in [inventory/replica-sets-path "items" 0 "spec" "template" "spec" "imagePullSecrets"]
                              [{"name" "missing-old-controller-dependency"}]))
        [result calls] (fixtures/capture! routes {})
        [pending] (fixtures/capture! (update-in routes [inventory/replica-sets-path "items" 0 "spec" "template" "spec"]
                                               dissoc "imagePullSecrets") {})]
    (is (anomaly/anomaly? result))
    (is (m/validate phase/KubernetesVerificationObservation pending))
    (is (= {"phase" "Pending"}
           (get-in pending [:kubernetes/inventory :kubernetes/pod-inventory "items" 0 "status"])))
    (is (some #(= "/api/v1/namespaces/staging/secrets/missing-old-controller-dependency" (wire/path %)) calls))))

(deftest ^{:stratum 0} malformed-template-reference-cannot-create-an-unvalidated-read-test
  (let [routes (assoc-in (fixtures/routes)
                         [inventory/deployment-path "spec" "template" "spec" "imagePullSecrets"]
                         [{"name" "../foreign"}])
        [result calls] (fixtures/capture! routes {})]
    (is (anomaly/anomaly? result))
    (is (= 6 (count calls)))))

(deftest ^{:stratum 0} current-pod-only-dependencies-are-read-independently-of-desired-templates-test
  (let [routes (assoc-in (fixtures/routes) [inventory/pods-path "items" 0 "spec" "imagePullSecrets"]
                         [{"name" "missing-pod-only-dependency"}])
        [result calls] (fixtures/capture! routes {})]
    (is (anomaly/anomaly? result))
    (is (some #(= "/api/v1/namespaces/staging/secrets/missing-pod-only-dependency" (wire/path %)) calls))))

(deftest ^{:stratum 0} optional-reference-absence-remains-distinct-from-a-required-use-test
  (let [routes (walk/postwalk
                 #(if (and (map? %) (contains? % "configMapKeyRef"))
                    (assoc-in % ["configMapKeyRef" "optional"] true) %)
                 (dissoc (fixtures/routes) wire/config-path))
        [optional] (fixtures/capture! routes {})
        [required] (fixtures/capture!
                     (assoc-in routes [inventory/pods-path "items" 0 "spec" "containers" 0
                                        "env" 0 "valueFrom" "configMapKeyRef" "optional"] false) {})]
    (is (m/validate phase/KubernetesVerificationObservation optional))
    (is (= ["Secret"] (mapv #(get % "kind") (:kubernetes/configuration optional))))
    (is (anomaly/anomaly? required))))

(deftest ^{:stratum 0} incomplete-node-list-and-required-configuration-absence-fail-closed-test
  (doseq [routes [(assoc-in (fixtures/routes) [fixtures/nodes-path "metadata" "continue"] "next")
                  (update-in (fixtures/routes) [fixtures/nodes-path] dissoc "metadata")
                  (dissoc (fixtures/routes) wire/secret-path)]]
    (let [[result] (fixtures/capture! routes {})]
      (is (anomaly/anomaly? result)))))

(deftest ^{:stratum 0} final-root-changes-after-dependency-capture-cannot-pass-as-original-target-test
  (let [reply wire/reply!]
    (doseq [[path nth-read] [[inventory/namespace-path 3] [inventory/deployment-path 3] [fixtures/cluster-path 2]]]
      (let [seen (atom {})
            [result] (with-redefs [wire/reply!
                                  (fn [routes calls options]
                                    (let [current (wire/path options)
                                          n (get (swap! seen update current (fnil inc 0)) current)
                                          routes (if (and (= current path) (= n nth-read))
                                                   (assoc-in routes [path "metadata" "resourceVersion"] "changed") routes)]
                                      (reply routes calls options)))]
                       (fixtures/capture! (fixtures/routes) {}))]
        (is (= :conflict (:anomaly/type result)))))))

(deftest ^{:stratum 0} selectors-and-invalid-targets-never-reach-kubernetes-test
  (doseq [overrides [{:kubernetes/pod-selector "app=only-one"}
                     {:kubernetes/base-url "http://cluster.invalid"}
                     {:kubernetes/namespace "foreign"}]]
    (let [[result calls] (fixtures/capture! (fixtures/routes) overrides)]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(deftest ^{:stratum 0} late-timeout-and-native-failures-never-become-a-partial-success-test
  (let [remaining io/remaining-ms
        reply wire/reply!
        expired (atom false)
        [result calls] (with-redefs [io/remaining-ms #(if @expired 0 (remaining %))
                                    wire/reply! (fn [routes calls options]
                                                  (let [result (reply routes calls options)]
                                                    (when (= fixtures/cluster-path (wire/path options))
                                                      (reset! expired true))
                                                    result))]
                         (fixtures/capture! (fixtures/routes) {}))]
    (is (= :timeout (:anomaly/type result)))
    (is (= 7 (count calls))))
  (let [[result] (fixtures/capture! (assoc (fixtures/routes) fixtures/nodes-path (java.io.IOException.)) {})]
    (is (= :unavailable (:anomaly/type result))))
  (doseq [failure [(AssertionError.) (InterruptedException.)]]
    (let [operation #(fixtures/capture! (assoc (fixtures/routes) fixtures/nodes-path failure) {})
          [thrown flagged?] (failures/interrupted-result operation)]
      (is (identical? failure thrown))
      (is (= (instance? InterruptedException failure) flagged?)))))
