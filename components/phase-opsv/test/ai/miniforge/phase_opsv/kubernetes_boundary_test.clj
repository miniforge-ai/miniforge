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
  (let [captured (assoc-in (fixture/snapshot) [:kubernetes/configuration 0 "unportable-extension"] (Object.))
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

(deftest ^{:stratum 0} test-cross-section-identity-and-configuration-conflicts-are-refused
  (let [captured (fixture/snapshot)
        pod (fixture/pod)
        changed-pod (assoc-in pod ["spec" "containers" 0 "image"] "registry.invalid/service:other")
        changed-image (assoc-in pod ["status" "containerStatuses" 0 "imageID"]
                                "containerd://sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        changed-node (assoc-in (fixture/node) ["status" "nodeInfo" "kubeletVersion"] "v1.34.0")
        namespace (:kubernetes/namespace captured)
        replacement (assoc-in namespace ["metadata" "uid"] "replacement-namespace")]
    (doseq [resource [changed-pod changed-image changed-node replacement]]
      (is (anomaly/anomaly?
            (phase/decode-kubernetes-fingerprint
              (update captured :kubernetes/configuration conj resource)))))
    (is (anomaly/anomaly?
          (phase/decode-kubernetes-fingerprint
            (update captured :kubernetes/configuration conj pod))))))

(deftest ^{:stratum 0} test-configuration-payloads-and-scope-must-be-supported
  (let [captured (fixture/snapshot)
        configuration (fixture/config-map)
        deployment (-> configuration
                       (assoc "apiVersion" "apps/v1" "kind" "Deployment" "spec" {})
                       (update "metadata" dissoc "namespace"))]
    (doseq [resource [(assoc configuration "data" 42)
                      (assoc configuration "apiVersion" "unsupported/v1")
                      (assoc configuration "kind" "Unknown")
                      (update (fixture/pod) "metadata" dissoc "namespace")
                      (assoc-in (fixture/node) ["metadata" "namespace"] "staging")
                      (assoc-in (:kubernetes/namespace captured) ["metadata" "namespace"] "staging")
                      deployment]]
      (is (anomaly/anomaly?
            (phase/decode-kubernetes-fingerprint
              (assoc captured :kubernetes/configuration [resource])))))))

(deftest ^{:stratum 0} test-runtime-observations-cannot-hide-in-configuration
  (let [captured (fixture/snapshot)]
    (doseq [resource [(fixture/pod) (fixture/node) (:kubernetes/namespace captured)]]
      (is (anomaly/anomaly?
            (phase/decode-kubernetes-fingerprint
              (update captured :kubernetes/configuration conj resource)))))))

(deftest ^{:stratum 0} test-non-pod-status-is-not-interpreted-as-container-observations
  (let [captured (fixture/snapshot)
        changed (assoc-in captured [:kubernetes/configuration 0 "status" "containerStatuses"] 42)]
    (is (= (phase/decode-kubernetes-fingerprint captured)
           (phase/decode-kubernetes-fingerprint changed)))))

(comment ::public-captured-target-admission)
