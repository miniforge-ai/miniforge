;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-container-test-support :as containers]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-public-boundary-returns-captured-identity-and-images
  (let [result (phase/decode-kubernetes-fingerprint (fixture/snapshot))]
    (is (not (anomaly/anomaly? result)))
    (is (= "staging-cluster" (:kubernetes/cluster-id result)))
    (is (= fixture/image-id (get-in result [:kubernetes/images 0 :image/id])))))

(deftest ^{:stratum 0} test-inconsistent-captures-never-produce-partial-fingerprints
  (let [captured (fixture/snapshot)]
    (doseq [[path value]
            [[[:kubernetes/cluster "metadata" "name"] "not-the-cluster-identity"]
             [[:kubernetes/nodes "items" 0 "metadata" "labels" fixture/pool-label] ""]
             [[:kubernetes/pods "items" 0 "spec" "nodeName"] "unobserved-node"]
             [[:kubernetes/pods "items" 0 "metadata" "namespace"] "another-namespace"]
             [[:kubernetes/pods "items" 0 "status" "containerStatuses" 0 "name"] "another-container"]
             [[:kubernetes/configuration 1 "metadata" "resourceVersion"] ""]
             [[:kubernetes/configuration 1 "metadata" "namespace"] "another-namespace"]]]
      (is (anomaly/anomaly? (phase/decode-kubernetes-fingerprint (assoc-in captured path value)))))))

(deftest ^{:stratum 0} test-unsupported-content-is-sanitized-without-echoing-captured-values
  (let [captured (assoc-in (fixture/snapshot) [:kubernetes/configuration 0 "data"] (Object.))
        result (phase/decode-kubernetes-fingerprint captured)]
    (is (= :invalid-input (:anomaly/type result)))
    (is (empty? (:anomaly/data result)))))

(deftest ^{:stratum 0} test-ambiguous-resource-identities-are-rejected
  (let [captured (fixture/snapshot)
        configuration (:kubernetes/configuration captured)
        replacement (assoc-in (first configuration) ["metadata" "uid"] "replacement-uid")]
    (doseq [value [(update-in captured [:kubernetes/nodes "items"] conj (fixture/node))
                  (update-in captured [:kubernetes/pods "items"] conj (fixture/pod))
                  (update captured :kubernetes/configuration conj replacement)
                  (assoc-in captured [:kubernetes/pods "items" 0 "metadata" "uid"] "node-uid")]]
      (is (anomaly/anomaly? (phase/decode-kubernetes-fingerprint value))))))

(deftest ^{:stratum 0} test-every-container-group-requires-matching-immutable-status
  (let [captured (fixture/snapshot)
        container (containers/container "helper")
        status (containers/status "helper" fixture/image-id)]
    (doseq [[spec-field status-field] [["initContainers" "initContainerStatuses"]
                                     ["ephemeralContainers" "ephemeralContainerStatuses"]]]
      (let [declared (assoc-in captured [:kubernetes/pods "items" 0 "spec" spec-field] [container])
            complete (assoc-in declared [:kubernetes/pods "items" 0 "status" status-field] [status])]
        (is (anomaly/anomaly? (phase/decode-kubernetes-fingerprint declared)))
        (is (= 2 (count (:kubernetes/images (phase/decode-kubernetes-fingerprint complete)))))
        (is (anomaly/anomaly?
              (phase/decode-kubernetes-fingerprint
                (assoc-in complete [:kubernetes/pods "items" 0 "status" status-field 0 "imageID"]
                          "registry.invalid/helper:release"))))))))

(comment ::public-captured-target-admission)
