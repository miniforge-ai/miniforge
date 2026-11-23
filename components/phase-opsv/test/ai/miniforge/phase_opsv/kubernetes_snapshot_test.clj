;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot-test
  (:require [ai.miniforge.phase-opsv.kubernetes-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as fixture]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-fingerprint-order-and-bookkeeping-are-stable
  (let [captured (fixture/snapshot)
        evidence (snapshot/fingerprint captured)
        reordered (update captured :kubernetes/configuration (comp vec reverse))
        bookkeeping (assoc-in captured [:kubernetes/pods "items" 0 "metadata" "resourceVersion"] "99")]
    (is (= fixture/image-id (get-in evidence [:kubernetes/images 0 :image/id])))
    (is (string? (get-in evidence [:kubernetes/namespace :resource/hash])))
    (is (= evidence (snapshot/fingerprint reordered)))
    (is (= evidence (snapshot/fingerprint bookkeeping)))))

(deftest ^{:stratum 0} test-physical-identity-runtime-images-and-configuration-drift-change-evidence
  (let [captured (fixture/snapshot)
        baseline (snapshot/fingerprint captured)
        image (str/replace fixture/image-id "sha256:a" "sha256:b")]
    (doseq [[path value] [[[:kubernetes/cluster "metadata" "uid"] "new-cluster"]
                          [[:kubernetes/namespace "metadata" "uid"] "new-namespace"]
                          [[:kubernetes/nodes "items" 0 "metadata" "uid"] "new-node"]
                          [[:kubernetes/nodes "items" 0 "metadata" "labels" fixture/pool-label] "pool-b"]
                          [[:kubernetes/nodes "items" 0 "status" "nodeInfo" "kubeletVersion"] "v1.34.0"]
                          [[:kubernetes/pods "items" 0 "status" "containerStatuses" 0 "imageID"] image]
                          [[:kubernetes/configuration 0 "data" "workers"] "8"]
                          [[:kubernetes/configuration 1 "metadata" "resourceVersion"] "43"]]]
      (is (not= baseline (snapshot/fingerprint (assoc-in captured path value)))))))

(deftest ^{:stratum 0} test-secret-values-and-annotations-do-not-enter-fingerprint-material
  (let [captured (fixture/snapshot)
        evidence (snapshot/fingerprint captured)
        changed (assoc-in captured [:kubernetes/configuration 1 "data" "token"] "another-secret-value")]
    (is (= evidence (snapshot/fingerprint changed)))
    (is (not (str/includes? (pr-str evidence) "private-value")))
    (is (not (str/includes? (pr-str evidence) "cHJpdmF0ZS12YWx1ZQ==")))))

(comment ::drift-sensitive-captured-fingerprint)
