;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-preparation-host-fixtures :as host]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-no-acquisition [input]
  (let [[result calls] (fixtures/observe input)]
    (is (anomaly/anomaly? result))
    (is (= {} (:anomaly/data result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} assert-host-retention [{:keys [output retained calls]}]
  (is (:valid? (evidence/validate-published-bundle (:opsv/evidence-bundle output))))
  (is (true? (get-in retained [:artifact/metadata :evidence/contains-pii?])))
  (is (not (.contains (pr-str output) "person@example.com")))
  (is (not (.contains (pr-str retained) "person@example.com")))
  (is (contains? (set (:opsv/artifact-refs output)) (:artifact/id retained)))
  (is (seq calls))
  (is (every? #(= :get (:method %)) calls)))

(deftest ^{:stratum 0} preparation-retains-originals-and-uses-recovery-not-candidate-bounds-test
  (let [input (fixtures/input)
        [result calls] (fixtures/observe input)
        projected (:projected input)
        raw (get-in result [:environment/raw-observations :kubernetes/verification])]
    (is (not (anomaly/anomaly? result)))
    (is (= [(:capture input)] [(:kubernetes/source raw)]))
    (is (= (:environment-fingerprint projected) (:environment-fingerprint result)))
    (is (= (:comparison/replica-observations projected) (:comparison/replica-observations result)))
    (is (= (select-keys (:request input) [:verification/id :candidate/hash :experiment-pack/hash])
           (select-keys result [:verification/id :candidate/hash :experiment-pack/hash])))
    (is (= {:replicas/min-count 1
            :replicas/max-count 3} (:comparison/bounds raw)))
    (is (= 1 (count calls)))
    (is (= (dissoc (:declaration input) :workflow/id) (second (first calls))))))

(deftest ^{:stratum 0} actual-acquisition-path-remains-get-only-and-rechecks-roots-test
  (let [input (fixtures/input)
        [result calls] (fixtures/observe-transport (fixtures/routes) input)
        raw (get-in result [:environment/raw-observations :kubernetes/verification])]
    (is (not (anomaly/anomaly? result)))
    (is (seq calls))
    (is (every? #(= :get (:method %)) calls))
    (is (not-any? #(contains? (:query-params %) :labelSelector) calls))
    (is (= (:runtime/target (:declaration input)) (:runtime/target result)))
    (is (= (:capture input) (:kubernetes/source raw)))
    (is (= ["/api/v1/namespaces/staging" "/apis/apps/v1/namespaces/staging/deployments/catalog"
            "/api/v1/namespaces/kube-system"] (mapv wire/path (take-last 3 calls))))))

(deftest ^{:stratum 0} repeated-preparation-reacquires-changed-configuration-without-adopting-a-new-baseline-test
  (let [input (fixtures/input)
        [before first-calls] (fixtures/observe-transport (:routes input) input)
        [after next-calls] (fixtures/observe-transport (fixtures/changed-configuration (:routes input)) input)]
    (is (not (anomaly/anomaly? before)))
    (is (not (anomaly/anomaly? after)))
    (is (seq first-calls))
    (is (seq next-calls))
    (is (not= (get-in before [:environment-fingerprint :config-hash])
              (get-in after [:environment-fingerprint :config-hash])))
    (is (= (:verification/id before) (:verification/id after)))
    (is (= (:environment-fingerprint before)
           (get-in input [:request :comparison/pin :comparison/contract :comparison/baseline])))))

(deftest ^{:stratum 0} missing-actual-runtime-cannot-borrow-expected-images-from-the-pin-test
  (let [input (fixtures/input)
        [result calls] (fixtures/observe-transport (fixtures/missing-runtime (:routes input)) input)]
    (is (anomaly/anomaly? result))
    (is (= {} (:anomaly/data result)))
    (is (seq calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} attempt-content-target-and-pin-mismatches-cannot-start-acquisition-test
  (let [input (fixtures/input)
        request (:request input)]
    (doseq [changed (fixtures/mismatched-requests request)]
      (assert-no-acquisition (assoc input :request changed)))
    (assert-no-acquisition (assoc input :request (fixtures/without-population-contract request)))
    (assert-no-acquisition (assoc-in input [:declaration :workflow/id] (random-uuid)))))

(deftest ^{:stratum 1} host-retains-protected-original-native-preparation-before-candidate-and-finalizes-test
  (host/with-context assert-host-retention))

(deftest ^{:stratum 1} language-metadata-cannot-hide-request-values-before-acquisition-test
  (let [input (fixtures/input)
        deferred (lazy-seq (throw (ex-info "must not realize hidden input" {})))
        request (with-meta (:request input) {:hidden deferred})
        declaration (with-meta (:declaration input) {:private "opaque-native-value"})]
    (assert-no-acquisition (assoc input :request request))
    (assert-no-acquisition (assoc input :declaration declaration))
    (is (not (realized? deferred)))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.kubernetes-preparation-integration-test))
