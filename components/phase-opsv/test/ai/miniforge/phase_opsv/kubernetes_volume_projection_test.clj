;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-projection-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.phase-opsv.kubernetes-volume-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-volume-scenarios :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} generated-service-account-bindings-have-a-versioned-stable-view-test
  (let [first-spec (fixtures/pod-spec "kube-api-access-bcdfg")
        second-spec (fixtures/pod-spec "kube-api-access-vwxz2")
        result (phase/project-kubernetes-pod-volume-bindings first-spec)]
    (is (= result (phase/project-kubernetes-pod-volume-bindings second-spec)))
    (is (= :kubernetes/service-account-volume-bindings-v1 (:kubernetes/projection result)))
    (is (= :kubernetes/service-account-volume (get-in result [:kubernetes/pod-spec "volumes" 0 "name"])))
    (is (= :kubernetes/service-account-volume
           (get-in result [:kubernetes/pod-spec "containers" 0 "volumeMounts" 0 "name"])))
    (is (= "kube-api-access-bcdfg" (get-in first-spec ["volumes" 0 "name"])))
    (is (= (get-in first-spec ["volumes" 0 "projected"])
           (get-in result [:kubernetes/pod-spec "volumes" 0 "projected"])))))

(deftest ^{:stratum 0} volume-content-and-all-unrelated-spec-fields-remain-sensitive-test
  (let [original (fixtures/pod-spec "kube-api-access-bcdfg")
        baseline (phase/project-kubernetes-pod-volume-bindings original)]
    (doseq [changed [(assoc-in original ["volumes" 0 "projected" "defaultMode"] 0)
                     (assoc-in original ["volumes" 0 "projected" "sources" 0 "serviceAccountToken" "audience"] "other")
                     (assoc-in original ["volumes" 0 "projected" "sources" 0 "serviceAccountToken" "expirationSeconds"] 1)
                     (assoc-in original ["volumes" 0 "projected" "sources" 1 "configMap" "name"] "other-ca")
                     (assoc-in original ["containers" 0 "volumeMounts" 0 "readOnly"] false)
                     (assoc-in original ["containers" 0 "volumeMounts" 0 "subPath"] "token")
                     (assoc original "serviceAccountName" "other")
                     (assoc original "nodeName" "other-node")
                     (assoc-in original ["containers" 0 "image"] "registry.invalid/service:other")]]
      (is (not= baseline (phase/project-kubernetes-pod-volume-bindings changed))))))

(deftest ^{:stratum 0} ordinary-volume-names-and-aliasing-are-never-collapsed-test
  (let [spec (fixtures/ordinary-volumes)
        result (phase/project-kubernetes-pod-volume-bindings spec)
        shared (assoc-in spec ["containers" 0 "volumeMounts" 1 "name"] "work")]
    (is (= spec (:kubernetes/pod-spec result)))
    (is (not= result (phase/project-kubernetes-pod-volume-bindings shared)))
    (is (= ["work" "cache"] (mapv #(get % "name") (get-in result [:kubernetes/pod-spec "volumes"]))))))

(deftest ^{:stratum 0} duplicate-unresolved-and-malformed-bindings-fail-closed-test
  (let [spec (fixtures/pod-spec "kube-api-access-bcdfg")]
    (doseq [changed [nil {} []
                     (update spec "volumes" #(conj % (first %)))
                     (assoc-in spec ["containers" 0 "volumeMounts" 0 "name"] "missing-volume")
                     (assoc-in spec ["volumes" 0 "projected" "sources"] "not-a-vector")
                     (assoc-in spec ["volumes" 0 "projected" "sources"] [false])
                     (update spec "volumes" conj (wire/service-account-volume "kube-api-access-vwxz2"))]]
      (is (anomaly/anomaly? (phase/project-kubernetes-pod-volume-bindings changed))))))

(deftest ^{:stratum 0} init-ephemeral-and-block-device-references-have-explicit-binding-behavior-test
  (let [spec (fixtures/all-container-groups "kube-api-access-bcdfg")
        result (phase/project-kubernetes-pod-volume-bindings spec)]
    (doseq [field ["containers" "initContainers" "ephemeralContainers"]]
      (is (= :kubernetes/service-account-volume
             (get-in result [:kubernetes/pod-spec field 0 "volumeMounts" 0 "name"])))))
  (let [spec (fixtures/block-device)
        changed (assoc-in spec ["containers" 0 "volumeDevices" 0 "name"] "missing-device")]
    (is (= spec (:kubernetes/pod-spec (phase/project-kubernetes-pod-volume-bindings spec))))
    (is (anomaly/anomaly? (phase/project-kubernetes-pod-volume-bindings changed)))
    (is (anomaly/anomaly?
          (phase/project-kubernetes-pod-volume-bindings
            (update-in spec ["containers" 0 "volumeDevices" 0] dissoc "devicePath"))))))

(deftest ^{:stratum 0} a-generated-looking-name-with-different-volume-content-stays-literal-test
  (let [name "kube-api-access-bcdfg"
        spec (assoc (fixtures/pod-spec name) "volumes" [(wire/empty-dir name)])
        result (phase/project-kubernetes-pod-volume-bindings spec)]
    (is (= spec (:kubernetes/pod-spec result)))))

(deftest ^{:stratum 0} suffixes-outside-the-native-generator-alphabet-stay-literal-test
  (let [native (phase/project-kubernetes-pod-volume-bindings (fixtures/pod-spec "kube-api-access-bcdfg"))]
    (doseq [character ["a" "e" "i" "o" "u" "y" "0" "1" "3"]]
      (let [spec (fixtures/pod-spec (str "kube-api-access-bcdf" character))
            result (phase/project-kubernetes-pod-volume-bindings spec)]
        (is (= spec (:kubernetes/pod-spec result)))
        (is (not= native result))))))

(deftest ^{:stratum 0} absent-and-empty-volume-fields-retain-their-distinction-test
  (let [spec (get (kube/pod) "spec")
        empty (assoc spec "volumes" [])]
    (is (= spec (:kubernetes/pod-spec (phase/project-kubernetes-pod-volume-bindings spec))))
    (is (= empty (:kubernetes/pod-spec (phase/project-kubernetes-pod-volume-bindings empty))))))

(comment ::projection-is-not-a-native-manifest-or-verification-verdict)
