;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-settings-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-reference-test-support :as fixture]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private expected-default-bounds
  "Shipped timeout, response-byte and resource-count defaults, in that order."
  [15000 1048576 1000])

(deftest ^{:stratum 0} test-only-explicit-https-origins-with-valid-ports-are-admitted
  (doseq [url ["http://cluster.invalid" "https://user:secret@cluster.invalid"
               "https://cluster.invalid/api" "https://cluster.invalid?q=secret"
               "https://cluster.invalid#secret" "https://cluster.invalid:0"
               "https://cluster.invalid:65536" "not-a-uri"]]
    (let [result (settings/load-settings (fixture/settings {:kubernetes/base-url url}))]
      (is (anomaly/anomaly? result))
      (is (empty? (:anomaly/data result)))))
  (doseq [url ["https://cluster.invalid:65535" "https://cluster.invalid/" "HTTPS://cluster.invalid"]]
    (is (not (anomaly/anomaly? (settings/load-settings (fixture/settings {:kubernetes/base-url url})))))))

(deftest ^{:stratum 0} test-malformed-scopes-bounds-and-credentials-are-refused
  (doseq [[key value] [[:kubernetes/namespace "other/namespace"]
                      [:kubernetes/namespace "other.namespace"]
                      [:kubernetes/namespace "-staging"]
                      [:kubernetes/namespace ""]
                      [:kubernetes/pod-selector " "]
                      [:kubernetes/node-pool-label ""]
                      [:kubernetes/timeout-ms 0]
                      [:kubernetes/max-response-bytes Integer/MAX_VALUE]
                      [:kubernetes/max-resources 0]
                      [:kubernetes/max-resources (inc Integer/MAX_VALUE)]
                      [:kubernetes/bearer-token "secret\r\nheader"]]]
    (is (anomaly/anomaly? (settings/load-settings (fixture/settings {key value}))))))

(deftest ^{:stratum 0} test-ordinary-configuration-failures-remain-sanitized
  (let [failure (failures/structured-failure (IllegalArgumentException. "credential-sentinel"))]
    (with-redefs [config/load-config-resource (partial failures/throw-failure! failure)]
      (let [result (settings/load-settings (fixture/settings))]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? (:anomaly/data result)))))))

(deftest ^{:stratum 0} test-configuration-failures-never-hide-critical-causes
  (let [failure (failures/structured-failure (AssertionError.))]
    (with-redefs [config/load-config-resource (partial failures/throw-failure! failure)]
      (is (thrown? AssertionError (settings/load-settings (fixture/settings))))))
  (let [failure (failures/structured-failure (InterruptedException.))
        operation (partial settings/load-settings (fixture/settings))]
    (with-redefs [config/load-config-resource (partial failures/throw-failure! failure)]
      (let [[thrown flagged?] (failures/interrupted-result operation)]
        (is (instance? InterruptedException thrown))
        (is flagged?)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-settings-require-an-explicit-target-and-bound-each-read
  (let [result (settings/load-settings (fixture/settings))]
    (is (= expected-default-bounds
           (mapv result [:kubernetes/timeout-ms :kubernetes/max-response-bytes :kubernetes/max-resources])))
    (is (= "https://cluster.invalid" (:kubernetes/base-url result))))
  (doseq [input [nil {} [] (dissoc (fixture/settings) :kubernetes/cluster-id)
                 (dissoc (fixture/settings) :kubernetes/namespace)
                 (dissoc (fixture/settings) :kubernetes/pod-selector)]]
    (is (anomaly/anomaly? (settings/load-settings input)))))

(comment ::explicit-kubernetes-settings-admission)
