;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-references-test
  (:require [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as fixture]
            [ai.miniforge.phase-opsv.kubernetes-references :as references]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-all-container-groups-contribute-configuration-dependencies
  (let [variable (fixture/environment "configMapKeyRef" "settings" false)
        secret (fixture/environment-from "secretRef" "credentials" true)
        container (fixture/container "service" [variable] [secret])]
    (doseq [field ["containers" "initContainers" "ephemeralContainers"]]
      (let [pod (fixture/pod {field [container]})
            result (references/configuration-references [pod])]
        (is (= ["ConfigMap" "Secret"] (mapv :reference/kind result)))
        (is (= ["settings" "credentials"] (mapv :reference/name result)))
        (is (= [false true] (mapv :reference/optional? result)))
        (is (= ["staging" "staging"] (mapv :reference/namespace result)))))))

(deftest ^{:stratum 0} test-volume-and-projected-reference-field-names-follow-the-api
  (doseq [[path name-key kind]
          [[["configMap"] "name" "ConfigMap"]
           [["secret"] "secretName" "Secret"]
           [["csi" "nodePublishSecretRef"] "name" "Secret"]
           [["azureFile"] "secretName" "Secret"]
           [["cephfs" "secretRef"] "name" "Secret"]
           [["cinder" "secretRef"] "name" "Secret"]
           [["flexVolume" "secretRef"] "name" "Secret"]
           [["iscsi" "secretRef"] "name" "Secret"]
           [["rbd" "secretRef"] "name" "Secret"]
           [["scaleIO" "secretRef"] "name" "Secret"]
           [["storageos" "secretRef"] "name" "Secret"]
           [["projected" "sources" 0 "secret"] "name" "Secret"]
           [["projected" "sources" 0 "configMap"] "name" "ConfigMap"]]]
    (let [volume (fixture/volume path name-key "referenced-object")
          pod (fixture/pod {"volumes" [volume]})
          result (references/configuration-references [pod])]
      (is (= [kind] (mapv :reference/kind result)))
      (is (= ["referenced-object"] (mapv :reference/name result))))))

(deftest ^{:stratum 0} test-required-use-wins-and-order-does-not-change-the-read-plan
  (let [optional (fixture/environment "secretKeyRef" "shared" true)
        required (fixture/environment "secretKeyRef" "shared" false)
        container (fixture/container "service" [optional required] [])
        pod (fixture/pod {"containers" [container]})
        optional-pod (fixture/pod {"containers" [(fixture/container "service" [optional] [])]})
        forward (references/configuration-references [pod optional-pod])
        reverse (references/configuration-references [optional-pod pod])]
    (is (= forward reverse))
    (is (= 1 (count forward)))
    (is (false? (:reference/optional? (first forward))))))

(deftest ^{:stratum 0} test-image-pull-secrets-and-empty-captures-remain-explicit
  (let [secret (fixture/referent "registry" false)
        pod (fixture/pod {"imagePullSecrets" [secret]})
        result (references/configuration-references [pod])]
    (is (= ["registry"] (mapv :reference/name result)))
    (is (= ["Secret"] (mapv :reference/kind result)))
    (is (= [] (references/configuration-references [])))
    (is (= [] (references/configuration-references [(fixture/pod {})])))))

(deftest ^{:stratum 0} test-projected-source-fixtures-preserve-the-native-array-shape
  (let [volume (fixture/volume ["projected" "sources" 0 "secret"] "name" "credentials")]
    (is (vector? (get-in volume ["projected" "sources"])))
    (is (= "credentials" (get-in volume ["projected" "sources" 0 "secret" "name"])))))

(deftest ^{:stratum 0} test-whole-environment-imports-keep-both-resource-kinds
  (let [config-map (fixture/environment-from "configMapRef" "all-settings" false)
        secret (fixture/environment-from "secretRef" "all-credentials" false)
        container (fixture/container "service" [] [config-map secret])
        result (references/configuration-references [(fixture/pod {"containers" [container]})])]
    (is (= ["ConfigMap" "Secret"] (mapv :reference/kind result)))
    (is (= ["all-settings" "all-credentials"] (mapv :reference/name result)))))

(comment ::configuration-reference-discovery)
