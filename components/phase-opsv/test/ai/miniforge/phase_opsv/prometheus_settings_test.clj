;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-settings-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-opsv.boundary.prometheus-settings :as settings]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} load-failure [failure]
  (with-redefs [config/load-config-resource (partial fixture/throw-failure! failure)]
    (settings/load-settings (fixture/settings))))

(deftest ^{:stratum 0} explicit-endpoints-load-validated-operational-defaults
  (let [loaded (settings/load-settings (fixture/settings))
        max-port (settings/load-settings (fixture/settings {:prometheus/base-url "https://metrics.invalid:65535/"}))]
    (is (= 15000 (:prometheus/timeout-ms loaded)))
    (is (= 1048576 (:prometheus/max-response-bytes loaded)))
    (is (= 10000 (:measurement/max-samples loaded)))
    (is (not (anomaly/anomaly? loaded)))
    (is (not (anomaly/anomaly? max-port)))))

(deftest ^{:stratum 0} malformed-settings-never-leak-secrets
  (doseq [overrides [{:prometheus/base-url "https://user:secret@metrics.invalid"}
                    {:prometheus/base-url "https://metrics.invalid?token=secret"}
                    {:prometheus/base-url "https://metrics.invalid#secret"}
                    {:prometheus/base-url "file:///secret"}
                    {:prometheus/base-url "https://metrics.invalid:65536/prometheus/"}
                    {:prometheus/base-url "http://metrics.invalid"
                     :prometheus/bearer-token "secret"}
                    {:prometheus/bearer-token "secret\r\nInjected: value"}
                    {:prometheus/timeout-ms 0} {:prometheus/timeout-ms 2147483648}
                    {:prometheus/max-response-bytes 2147483647}
                    {:measurement/max-samples 1} {:unknown "secret"}]]
    (let [result (settings/load-settings (fixture/settings overrides))]
      (is (anomaly/anomaly? result))
      (is (not (re-find #"secret" (pr-str result)))))))

(deftest ^{:stratum 0} endpoint-is-never-invented
  (doseq [overrides [nil {}]]
    (is (anomaly/anomaly? (settings/load-settings overrides)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-settings-loading-preserves-wrapped-critical-causes
  (is (thrown? AssertionError (load-failure (Exception. "secret" (AssertionError.)))))
  (try
    (let [[failure flagged?] (fixture/interrupted-result
                              (partial load-failure (Exception. "secret" (InterruptedException.))))]
      (is (instance? InterruptedException failure))
      (is flagged?))
    (finally (Thread/interrupted))))

(deftest ^{:stratum 1} test-ordinary-and-structured-configuration-failures-are-sanitized
  (doseq [failure [(Exception. "secret") (fixture/structured-failure nil)]]
    (let [result (load-failure failure)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (not (re-find #"secret" (pr-str result)))))))

(comment ::admitted-transport-settings)
