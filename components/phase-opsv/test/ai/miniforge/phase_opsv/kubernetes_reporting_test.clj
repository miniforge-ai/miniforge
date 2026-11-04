;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.exception-policy :as exception-policy]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} report-outcome [input]
  (try+ (phase/prepare-kubernetes-reporting input)
        (catch InterruptedException interrupted
          (.interrupt (Thread/currentThread))
          (throw interrupted))
        (catch exception-policy/nonfatal? failure failure)))

(defn- ^{:stratum 0} with-literal [value]
  (-> (kube/pod)
      (assoc-in ["spec" "containers" 0 "env"] [{"name" "SETTING" "value" value}])
      (assoc-in ["spec" "containers" 0 "command"] [value])
      (assoc-in ["spec" "containers" 0 "resources"] {"limits" {"cpu" "1"}})))

(defn- ^{:stratum 0} controller [spec]
  (let [metadata (kube/metadata "catalog" "staging" "deployment-uid")]
    (assoc (kube/kube-resource "Deployment" metadata {"spec" spec}) "apiVersion" "apps/v1")))

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

(deftest ^{:stratum 0} metadata-free-resource-envelopes-do-not-bypass-payload-classification-test
  (let [input (dissoc (kube/kube-resource "Status" {} {"message" "opaque-native-value"}) "metadata")
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "message" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 0} foreign-top-level-type-and-immutable-values-are-payloads-not-public-native-headers-test
  (let [input (assoc (kube/kube-resource "Pod" {} {}) "apiVersion" "example.invalid/v1"
                     "type" "opaque-native-value" "immutable" "opaque-native-value")
        prepared (phase/prepare-kubernetes-reporting input)]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "type" :kubernetes.reporting/secret])))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "immutable" :kubernetes.reporting/secret])))))

(deftest ^{:stratum 0} owner-reference-identity-stays-public-while-reference-extensions-are-confidential-test
  (let [reference (assoc (inventory/non-controller-rbac-reference) "note" "opaque-native-value")
        input (assoc-in (kube/pod) ["metadata" "ownerReferences"] [reference])
        prepared (phase/prepare-kubernetes-reporting input)
        observed (get-in prepared [:artifact/content :kubernetes/reporting "metadata" "ownerReferences" 0])]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (= "discovery-role-uid" (get observed "uid")))
    (is (= "system:discovery" (get observed "name")))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))))

(deftest ^{:stratum 0} semantic-runtime-and-ownership-payloads-are-not-plaintext-exemptions-test
  (doseq [field [:resource/runtime :resource/ownership-and-lifecycle]]
    (let [input {:captured {field {"extension" "opaque-native-value"}}}
          prepared (phase/prepare-kubernetes-reporting input)]
      (is (evidence/valid-protected-artifact-content? prepared))
      (is (not (.contains (pr-str prepared) "opaque-native-value")))
      (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting :captured field :kubernetes.reporting/secret]))))))

(deftest ^{:stratum 0} public-identity-fields-do-not-exempt-composite-children-test
  (doseq [field ["name" "uid" "resourceVersion"]]
    (let [prepared (phase/prepare-kubernetes-reporting (assoc-in (kube/pod) ["metadata" field] {"note" "opaque-native-value"}))]
      (is (evidence/valid-protected-artifact-content? prepared))
      (is (not (.contains (pr-str prepared) "opaque-native-value"))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-init-and-ephemeral-groups-receive-the-same-payload-protection-test
  (let [pod (with-literal "opaque-native-value")
        container (get-in pod ["spec" "containers" 0])]
    (doseq [field ["initContainers" "ephemeralContainers"]]
      (let [prepared (phase/prepare-kubernetes-reporting (assoc-in pod ["spec" field] [container]))]
        (is (evidence/valid-protected-artifact-content? prepared))
        (is (not (.contains (pr-str prepared) "opaque-native-value")))
        (is (= "pod-uid" (get-in prepared [:artifact/content :kubernetes/reporting "metadata" "uid"])))))))

(deftest ^{:stratum 1} native-literal-environment-and-commands-do-not-depend-on-key-name-detection-test
  (let [input (with-literal "opaque-native-value")
        prepared (phase/prepare-kubernetes-reporting input)
        report (get-in prepared [:artifact/content :kubernetes/reporting])]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (not (.contains (pr-str prepared) "opaque-native-value")))
    (is (= "service-a" (get-in report ["metadata" "name"])))
    (is (= "pod-uid" (get-in report ["metadata" "uid"])))
    (is (= "opaque-native-value" (get-in input ["spec" "containers" 0 "env" 0 "value"])))
    (is (= (redaction/marker) (get-in report ["spec" :kubernetes.reporting/secret])))))

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
        deployment (controller {"replicas" 2 "template" {"spec" spec}})
        unknown (kube/kube-resource "Other" (get pod "metadata") {"spec" {"arbitrary" "opaque-native-value"}})
        prepared (phase/prepare-kubernetes-reporting
                   {:controller deployment
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

(deftest ^{:stratum 1} string-keyed-sorted-maps-are-normalized-without-changing-originals-test
  (let [input (walk/postwalk #(if (map? %) (into (sorted-map) %) %) (with-literal "opaque-native-value"))]
    (doseq [[value path] [[input [:artifact/content :kubernetes/reporting "spec" :kubernetes.reporting/secret]]
                          [(sorted-map :captured input)
                           [:artifact/content :kubernetes/reporting :captured "spec" :kubernetes.reporting/secret]]]]
      (let [prepared (report-outcome value)]
        (is (evidence/valid-protected-artifact-content? prepared))
        (is (= (redaction/marker) (get-in prepared path)))))
    (is (= "opaque-native-value" (get-in input ["spec" "containers" 0 "env" 0 "value"])))))

(deftest ^{:stratum 1} unchecked-composite-public-fields-cannot-retain-confidential-children-test
  (let [pod (kube/pod)
        deployment (controller {"replicas" 2 "strategy" {"note" "opaque-native-value"}})]
    (doseq [input [(assoc-in pod ["spec" "containers" 0 "resources"] {"note" "opaque-native-value"})
                   deployment
                   (assoc-in pod ["metadata" "generation"] {"note" "opaque-native-value"})
                   (assoc-in pod ["metadata" "finalizers"] [{"note" "opaque-native-value"}])]]
      (let [prepared (phase/prepare-kubernetes-reporting input)]
        (is (evidence/valid-protected-artifact-content? prepared))
        (is (not (.contains (pr-str prepared) "opaque-native-value")))))))

(deftest ^{:stratum 1} standalone-and-nested-items-without-type-meta-still-withhold-native-payloads-test
  (let [pod (dissoc (with-literal "opaque-native-value") "apiVersion" "kind")]
    (doseq [input [pod {:captured pod}]]
      (let [prepared (phase/prepare-kubernetes-reporting input)]
        (is (evidence/valid-protected-artifact-content? prepared))
        (is (not (.contains (pr-str prepared) "opaque-native-value")))))))

(deftest ^{:stratum 1} public-controller-counts-require-native-nonnegative-int32-values-test
  (doseq [value [0 2]]
    (let [prepared (phase/prepare-kubernetes-reporting (controller {"replicas" value}))]
      (is (evidence/valid-protected-artifact-content? prepared))
      (is (= value (get-in prepared [:artifact/content :kubernetes/reporting "spec" "replicas"])))))
  (doseq [value [-1 (inc (long Integer/MAX_VALUE)) "opaque-native-value" {"note" "opaque-native-value"}]]
    (let [prepared (phase/prepare-kubernetes-reporting (controller {"replicas" value}))]
      (is (evidence/valid-protected-artifact-content? prepared))
      (is (not (.contains (pr-str prepared) "opaque-native-value")))
      (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "spec" "replicas" :kubernetes.reporting/secret]))))))

(deftest ^{:stratum 1} foreign-controller-identities-do-not-publicize-desired-count-fields-test
  (let [prepared (phase/prepare-kubernetes-reporting (assoc (controller {"replicas" 2}) "apiVersion" "example.invalid/v1"))]
    (is (evidence/valid-protected-artifact-content? prepared))
    (is (= (redaction/marker) (get-in prepared [:artifact/content :kubernetes/reporting "spec" :kubernetes.reporting/secret])))))
