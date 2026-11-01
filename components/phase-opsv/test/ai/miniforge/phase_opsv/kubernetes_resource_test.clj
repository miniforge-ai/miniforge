;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-resource-test
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-test-support :refer [config-map secret]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-resource-replacement-changes-physical-identity
  (let [original (config-map)
        replacement (assoc-in original ["metadata" "uid"] "replacement-uid")]
    (is (= ["v1" "ConfigMap" "staging" "settings" "config-uid"]
           (resource/identity-key original)))
    (is (not= (resource/identity-key original) (resource/identity-key replacement)))))

(deftest ^{:stratum 0} test-bookkeeping-and-status-do-not-change-desired-configuration
  (let [original (config-map)
        observed (-> original
                     (assoc-in ["metadata" "resourceVersion"] "99")
                     (assoc-in ["metadata" "creationTimestamp"] "2026-11-01T00:00:00Z")
                     (assoc "status" {"observedGeneration" 2}))]
    (is (= (resource/configuration original) (resource/configuration observed)))
    (is (not= (resource/configuration original)
              (resource/configuration (assoc-in original ["data" "workers"] "8"))))))

(deftest ^{:stratum 0} test-secret-evidence-tracks-revisions-without-hashing-secret-values
  (let [original (secret)
        projection (resource/configuration original)]
    (is (= {:secret/resource-version "42"} projection))
    (is (not (str/includes? (pr-str projection) "private-value")))
    (is (not (str/includes? (pr-str projection) "cHJpdmF0ZS12YWx1ZQ==")))
    (is (not= projection
              (resource/configuration (assoc-in original ["metadata" "resourceVersion"] "43"))))))

(comment ::stable-kubernetes-resource-projections)
