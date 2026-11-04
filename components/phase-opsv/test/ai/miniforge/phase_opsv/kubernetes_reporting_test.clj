;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-literal [value]
  (-> (kube/pod)
      (assoc-in ["spec" "containers" 0 "env"] [{"name" "SETTING" "value" value}])
      (assoc-in ["spec" "containers" 0 "command"] [value])
      (assoc-in ["spec" "containers" 0 "resources"] {"limits" {"cpu" "1"}})))

(deftest ^{:stratum 0} native-secret-and-config-payloads-use-shared-protected-material-test
  (let [snapshot (kube/snapshot)
        prepared (phase/prepare-kubernetes-reporting snapshot)
        content (:artifact/content prepared)
        rendered (pr-str prepared)
        secret (get-in content [:kubernetes/reporting :kubernetes/configuration 1])]
    (is (not (anomaly/anomaly? prepared)))
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (= :kubernetes/data-minimized-reporting-v1 (:kubernetes/reporting-profile content)))
    (is (= "secret-uid" (get-in secret ["metadata" "uid"])))
    (is (= "42" (get-in secret ["metadata" "resourceVersion"])))
    (is (not (.contains rendered "private-value")))
    (is (not (.contains rendered "cHJpdmF0ZS12YWx1ZQ==")))
    (is (= (redaction/marker) (get-in secret ["data" :kubernetes.reporting/secret])))
    (is (true? (get-in prepared [:artifact/metadata :compliance/sensitive-data])))
    (is (= :redacted (get-in prepared [:artifact/metadata :compliance/pii-handling])))))

(deftest ^{:stratum 0} opaque-language-metadata-deferred-and-complex-key-inputs-fail-without-echo-test
  (doseq [input [(Object.) (lazy-seq ["uninspectable"])
                 (with-meta {:visible true} {:private "uninspectable"})
                 {["collection-key"] "uninspectable"}]]
    (let [result (phase/prepare-kubernetes-reporting input)]
      (is (anomaly/anomaly? result))
      (is (not (.contains (pr-str result) "uninspectable"))))))

(deftest ^{:stratum 0} anomaly-shaped-material-is-report-data-not-pipeline-control-test
  (let [input {:anomaly/type :unavailable :native (kube/pod)}
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (= :unavailable (get-in prepared [:artifact/content :kubernetes/reporting :anomaly/type])))))

(deftest ^{:stratum 0} confidential-native-scalars-do-not-inherit-generic-scalar-preservation-test
  (doseq [value [nil false 42]]
    (let [input (kube/kube-resource "Other" (kube/metadata "test" nil "test-uid") {"spec" value})
          prepared (phase/prepare-kubernetes-reporting input)]
      (is (evidence/valid-protected-artifact-content? prepared))
      (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "spec" :kubernetes.reporting/secret]))))))

(deftest ^{:stratum 0} native-labels-and-annotations-are-classified-without-secret-looking-keys-test
  (let [input (-> (kube/pod)
                  (assoc-in ["metadata" "labels"] {"group" "opaque-native-meta"})
                  (assoc-in ["metadata" "annotations"] {"note" "opaque-native-meta"}))
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-meta")))
    (is (.contains (pr-str input) "opaque-native-meta"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-init-and-ephemeral-groups-receive-the-same-payload-protection-test
  (let [pod (with-literal "opaque-native-value")
        container (get-in pod ["spec" "containers" 0])]
    (doseq [field ["initContainers" "ephemeralContainers"]]
      (let [prepared (phase/prepare-kubernetes-reporting (assoc-in pod ["spec" field] [container]))]
        (is (evidence/valid-protected-artifact-content? prepared))
        (is (not (.contains (pr-str prepared) "opaque-native-value")))
        (is (= "service" (get-in prepared [:artifact/content :kubernetes/reporting "spec" field 0 "name"])))))))

(deftest ^{:stratum 1} native-literal-environment-and-commands-do-not-depend-on-key-name-detection-test
  (let [input (with-literal "opaque-native-value")
        prepared (phase/prepare-kubernetes-reporting input)
        report (get-in prepared [:artifact/content :kubernetes/reporting])]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= "service" (get-in report ["spec" "containers" 0 "name"])))
    (is (= {"limits" {"cpu" "1"}} (get-in report ["spec" "containers" 0 "resources"])))
    (is (= "opaque-native-value" (get-in input ["spec" "containers" 0 "env" 0 "value"])))
    (is (= (redaction/marker) (get-in report ["spec" "containers" 0 "env" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 1} original-comparison-differences-survive-while-reporting-values-are-withheld-test
  (let [first-input (with-literal "opaque-native-first")
        second-input (with-literal "opaque-native-second")
        first-report (phase/prepare-kubernetes-reporting first-input)
        second-report (phase/prepare-kubernetes-reporting second-input)]
    (is (not= (artifact/content-digest first-input) (artifact/content-digest second-input)))
    (is (= first-report second-report))
    (is (evidence/valid-protected-artifact-content? first-report))))

(deftest ^{:stratum 1} original-pii-findings-are-scanned-before-native-field-redaction-test
  (let [prepared (phase/prepare-kubernetes-reporting (with-literal "123-45-6789"))]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "123-45-6789")))
    (is (true? (get-in prepared [:artifact/metadata :evidence/contains-pii?])))
    (is (seq (get-in prepared [:artifact/metadata :compliance/sensitive-findings])))))

(deftest ^{:stratum 1} desired-templates-versioned-pod-projections-and-unknown-native-kinds-are-safe-test
  (let [pod (with-literal "opaque-native-value")
        spec (get pod "spec")
        metadata (get pod "metadata")
        desired {"spec" {"replicas" 2 "template" {"spec" spec}}}
        controller (assoc (kube/kube-resource "Deployment" metadata desired) "apiVersion" "apps/v1")
        unknown (kube/kube-resource "Other" metadata {"spec" {"arbitrary" "opaque-native-value"}})
        prepared (phase/prepare-kubernetes-reporting
                   {:controller controller
                    :semantic {:kubernetes/pod-spec spec}
                    :unknown unknown})]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= 2 (get-in prepared [:artifact/content :kubernetes/reporting :controller "spec" "replicas"])))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting :unknown "spec" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 1} failed-shared-redaction-never-returns-classified-native-payloads-test
  (let [input (with-literal "opaque-native-value")
        result (with-redefs [redaction/redact identity] (phase/prepare-kubernetes-reporting input))]
    (is (= :unavailable (:anomaly/type result)))
    (is (not (.contains (pr-str result) "opaque-native-value")))))

(deftest ^{:stratum 1} native-lists-with-omitted-item-type-meta-still-protect-payloads-test
  (let [pod (dissoc (with-literal "opaque-native-value") "apiVersion" "kind")
        input (kube/resource-list "PodList" [pod])
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "items" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 1} foreign-api-kinds-do-not-receive-native-public-field-treatment-test
  (let [input (-> (with-literal "opaque-native-value")
                  (assoc "apiVersion" "example.invalid/v1")
                  (assoc-in ["spec" "containers" 0 "resources"] {"custom" "opaque-native-value"}))
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "spec" :kubernetes.reporting/secret])))))
