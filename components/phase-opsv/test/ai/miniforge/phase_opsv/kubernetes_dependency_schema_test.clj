;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-dependency-schema-test
  (:require [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as fixture]
            [ai.miniforge.phase-opsv.kubernetes-dependency-schema :as schema]
            [malli.core :as m]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} volume-pods [path name-key optional?]
  (let [volume (fixture/volume path name-key "referenced-object")
        reference (get-in volume path)
        referent (cond-> reference (some? optional?) (assoc "optional" optional?))
        completed-volume (assoc-in volume path referent)]
    (assoc-in (fixture/api-pods false) ["items" 0 "spec" "volumes"] [completed-volume])))

(deftest ^{:stratum 0} test-native-reference-shapes-are-admitted
  (is (m/validate schema/PodList (fixture/api-pods false))))

(deftest ^{:stratum 0} test-native-list-items-need-no-item-level-type-metadata
  (let [pods (update (fixture/api-pods false) "items"
                     (partial mapv #(dissoc % "apiVersion" "kind")))]
    (is (m/validate schema/PodList pods))))

(deftest ^{:stratum 0} test-pod-namespaces-use-the-native-dns-label-domain
  (doseq [namespace ["staging/other" "staging.other" "Staging" "-staging"]]
    (let [pods (assoc-in (fixture/api-pods false) ["items" 0 "metadata" "namespace"] namespace)]
      (is (not (m/validate schema/PodList pods))))))

(deftest ^{:stratum 0} test-key-selectors-require-the-native-data-key
  (let [pods (fixture/api-pods false)
        malformed (update-in pods ["items" 0 "spec" "containers" 0 "env" 0 "valueFrom" "configMapKeyRef"] dissoc "key")]
    (is (not (m/validate schema/PodList malformed)))))

(deftest ^{:stratum 0} test-local-object-references-cannot-acquire-optional-semantics
  (let [pods (assoc-in (fixture/api-pods false) ["items" 0 "spec" "imagePullSecrets"]
                      [{"name" "registry" "optional" true}])]
    (is (not (m/validate schema/PodList pods)))))

(deftest ^{:stratum 0} test-unmodelled-dependency-surfaces-are-refused
  (doseq [[path value] [[["containers" 0 "env" 0 "valueFrom"] {"unmodelledRef" {"name" "outside"}}]
                      [["volumes"] [{"name" "disk" "persistentVolumeClaim" {"claimName" "outside"}}]]
                      [["volumes"] [{"name" "roots" "projected" {"sources" [{"clusterTrustBundle" {"name" "outside" "path" "roots.pem"}}]}}]]]]
    (let [pods (assoc-in (fixture/api-pods false) (into ["items" 0 "spec"] path) value)]
      (is (not (m/validate schema/PodList pods))))))

(deftest ^{:stratum 0} test-native-image-pull-local-reference-is-admitted
  (let [pods (assoc-in (fixture/api-pods false) ["items" 0 "spec" "imagePullSecrets"]
                      [(fixture/local-reference "registry")])]
    (is (m/validate schema/PodList pods))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-supported-native-volume-references-are-admitted
  (doseq [[path name-key _kind optional-selector?] fixture/volume-cases
          optional? (if optional-selector? [nil false true] [nil])]
    (is (m/validate schema/PodList (volume-pods path name-key optional?))
        (pr-str [path optional?]))))

(deftest ^{:stratum 1} test-required-volume-local-references-cannot-become-optional
  (doseq [[path name-key _kind optional-selector?] fixture/volume-cases
          :when (not optional-selector?)
          optional? [false true]]
    (is (not (m/validate schema/PodList (volume-pods path name-key optional?))))))

(comment ::native-reference-field-admission)
