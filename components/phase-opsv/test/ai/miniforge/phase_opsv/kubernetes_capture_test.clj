;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-capture-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as fixture]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-public-capture-binds-the-target-and-reads-all-configuration
  (let [[result calls] (fixture/observe! (fixture/routes false) {})]
    (is (not (anomaly/anomaly? result)))
    (is (= "staging-cluster" (:kubernetes/cluster-id result)))
    (is (= 2 (count (:kubernetes/configuration result))))
    (is (= 6 (count calls)))
    (is (every? #(= :get (:method %)) calls))
    (is (= {:labelSelector "app=service" :limit 1000} (:query-params (first calls))))
    (is (not (re-find #"private-value|cHJpdmF0ZS12YWx1ZQ==" (pr-str result))))))

(deftest ^{:stratum 0} test-partial-foreign-and-malformed-pod-lists-stop-before-derived-reads
  (doseq [[path value] [[["metadata" "continue"] "more-results"]
                      [["items" 0 "metadata" "namespace"] "another-namespace"]
                      [["items" 0 "spec" "nodeName"] "../other-resource"]
                      [["items" 0 "spec" "containers" 0 "env" 0 "valueFrom" "configMapKeyRef" "name"] ""]
                      [["items" 0 "spec" "containers" 0 "env" 0 "valueFrom"] {"fileKeyRef" {"volumeName" "unmodelled" "path" "environment" "key" "workers"}}]
                      [["items" 0 "spec" "volumes"] [{"name" "disk" "persistentVolumeClaim" {"claimName" "outside-capture"}}]]]]
    (let [routes (assoc-in (fixture/routes false) (into [fixture/pods-path] path) value)
          [result calls] (fixture/observe! routes {})]
      (is (anomaly/anomaly? result))
      (is (= 1 (count calls))))))

(deftest ^{:stratum 0} test-total-resource-budget-stops-the-capture-before-further-reads
  (let [[result calls] (fixture/observe! (fixture/routes false) {:kubernetes/max-resources 2})]
    (is (anomaly/anomaly? result))
    (is (= 1 (count calls)))))

(deftest ^{:stratum 0} test-required-absence-and-wrong-identities-halt-configuration-reads
  (doseq [routes [(dissoc (fixture/routes false) fixture/config-path)
                  (assoc-in (fixture/routes false) [fixture/config-path "metadata" "name"] "substituted")]]
    (let [[result calls] (fixture/observe! routes {})]
      (is (anomaly/anomaly? result))
      (is (not (some #{fixture/secret-path} (map fixture/path calls)))))))

(deftest ^{:stratum 0} test-optional-absence-is-admitted-and-later-appearance-changes-the-fingerprint
  (let [routes (fixture/routes true)
        [absent] (fixture/observe! (dissoc routes fixture/config-path) {})
        [present] (fixture/observe! routes {})]
    (is (not (anomaly/anomaly? absent)))
    (is (= 1 (count (:kubernetes/configuration absent))))
    (is (not= (:kubernetes/configuration absent) (:kubernetes/configuration present)))))

(deftest ^{:stratum 0} test-expired-captures-and-invalid-settings-start-no-api-reads
  (let [[invalid calls] (fixture/observe! (fixture/routes false) {:kubernetes/pod-selector ""})]
    (is (anomaly/anomaly? invalid))
    (is (empty? calls)))
  (with-redefs [io/remaining-ms (constantly 0)]
    (let [[result calls] (fixture/observe! (fixture/routes false) {})]
      (is (= :timeout (:anomaly/type result)))
      (is (empty? calls)))))

(deftest ^{:stratum 0} test-critical-causes-cross-the-public-acquisition-boundary
  (doseq [failure [(AssertionError.) (InterruptedException.)]]
    (let [routes (assoc (fixture/routes false) fixture/pods-path failure)
          operation (partial fixture/observe! routes {})
          [thrown flagged?] (failures/interrupted-result operation)]
      (is (identical? failure thrown))
      (is (= (instance? InterruptedException failure) flagged?)))))

(comment ::explicit-bounded-acquisition-acceptance)
