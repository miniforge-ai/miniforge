;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.schema-test
  "Tests for progress-detector Malli schemas.

   Anomaly validation lives on `ai.miniforge.anomaly.interface` and is
   exercised in that component's tests — schemas here cover only the
   progress-detector-specific shapes."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.schema :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories

(defn- now [] (java.time.Instant/now))

(defn- valid-detector-anomaly-data []
  {:detector/kind     :detector/tool-loop
   :detector/version  "stage-1.0"
   :anomaly/class     :mechanical
   :anomaly/severity  :error
   :anomaly/category  :anomalies.agent/tool-loop
   :anomaly/evidence  {:summary       "Read same file 6 times unchanged"
                       :event-ids     []
                       :fingerprint   "abc123"
                       :threshold     {:n 5 :window 10}
                       :raw-log-ref   "local://runs/x"
                       :redacted?     true}})

(defn- valid-observation []
  {:tool/id          :tool/Bash
   :seq              1
   :timestamp        (now)
   :tool/duration-ms 150})

(defn- valid-tool-profile []
  {:tool/id            :tool/Bash
   :determinism        :environment-dependent
   :anomaly/categories #{:anomalies.agent/repeated-failure}
   :timeout-ms         120000})

;------------------------------------------------------------------------------ Layer 1
;; DetectorAnomalyData (the shape that lives under :anomaly/data)

(deftest valid-detector-anomaly-data-test
  (testing "complete payload is valid"
    (is (true? (sut/valid-detector-anomaly-data? (valid-detector-anomaly-data)))))

  (testing "evidence with only :summary is valid (other evidence keys optional)"
    (is (true? (sut/valid-detector-anomaly-data?
                (assoc (valid-detector-anomaly-data)
                       :anomaly/evidence {:summary "minimal"}))))))

(deftest invalid-detector-anomaly-data-test
  (testing "missing :detector/kind fails"
    (is (false? (sut/valid-detector-anomaly-data?
                 (dissoc (valid-detector-anomaly-data) :detector/kind)))))

  (testing "missing :detector/version fails"
    (is (false? (sut/valid-detector-anomaly-data?
                 (dissoc (valid-detector-anomaly-data) :detector/version)))))

  (testing "invalid :anomaly/class value fails"
    (is (false? (sut/valid-detector-anomaly-data?
                 (assoc (valid-detector-anomaly-data) :anomaly/class :NOT_VALID)))))

  (testing "invalid :anomaly/severity value fails"
    (is (false? (sut/valid-detector-anomaly-data?
                 (assoc (valid-detector-anomaly-data) :anomaly/severity :critical)))
        ":critical is not in the spec's severity vocabulary"))

  (testing "missing :anomaly/evidence fails"
    (is (false? (sut/valid-detector-anomaly-data?
                 (dissoc (valid-detector-anomaly-data) :anomaly/evidence))))))

(deftest detector-class-vocabulary-test
  (testing "spec-defined classes are accepted"
    (doseq [cls [:mechanical :heuristic]]
      (is (true? (sut/valid-detector-anomaly-data?
                  (assoc (valid-detector-anomaly-data) :anomaly/class cls)))
          (str cls " should be a valid detector class")))))

(deftest detector-severity-ladder-test
  (testing "all four spec-defined severities are accepted"
    (doseq [sev [:info :warn :error :fatal]]
      (is (true? (sut/valid-detector-anomaly-data?
                  (assoc (valid-detector-anomaly-data) :anomaly/severity sev)))
          (str sev " should be a valid severity")))))

(deftest explain-detector-anomaly-data-test
  (testing "returns nil for valid payload"
    (is (nil? (sut/explain-detector-anomaly-data (valid-detector-anomaly-data)))))

  (testing "returns errors for invalid payload"
    (is (some? (sut/explain-detector-anomaly-data
                (dissoc (valid-detector-anomaly-data) :detector/kind))))))

;------------------------------------------------------------------------------ Layer 1
;; Observation schema

(deftest valid-observation-test
  (testing "valid observation passes validation"
    (is (true? (sut/valid-observation? (valid-observation)))))

  (testing "observation with optional fields is valid"
    (is (true? (sut/valid-observation?
                (assoc (valid-observation)
                       :tool/input  {:cmd "ls"}
                       :tool/output "file.txt\n"
                       :tool/error? false))))))

(deftest invalid-observation-test
  (testing "missing :tool/id fails"
    (is (false? (sut/valid-observation? (dissoc (valid-observation) :tool/id)))))

  (testing "missing :seq fails"
    (is (false? (sut/valid-observation? (dissoc (valid-observation) :seq)))))

  (testing "missing :timestamp fails"
    (is (false? (sut/valid-observation? (dissoc (valid-observation) :timestamp))))))

(deftest explain-observation-test
  (testing "returns nil for valid observation"
    (is (nil? (sut/explain-observation (valid-observation)))))

  (testing "returns errors for invalid observation"
    (is (some? (sut/explain-observation {})))))

;------------------------------------------------------------------------------ Layer 1
;; ToolProfile schema

(deftest valid-tool-profile-test
  (testing "valid tool profile passes validation"
    (is (true? (sut/valid-tool-profile? (valid-tool-profile)))))

  (testing "minimal profile (only :tool/id + :determinism) is valid"
    (is (true? (sut/valid-tool-profile?
                {:tool/id :tool/Read :determinism :stable-with-resource-version})))))

(deftest invalid-tool-profile-test
  (testing "missing :tool/id fails"
    (is (false? (sut/valid-tool-profile? (dissoc (valid-tool-profile) :tool/id)))))

  (testing "missing :determinism fails"
    (is (false? (sut/valid-tool-profile? (dissoc (valid-tool-profile) :determinism)))))

  (testing "invalid :determinism value fails"
    (is (false? (sut/valid-tool-profile?
                 (assoc (valid-tool-profile) :determinism :NOT_VALID))))))

(deftest determinism-vocabulary-test
  (testing "all four spec-defined determinism levels are accepted"
    (doseq [det [:stable-with-resource-version
                 :stable-ish
                 :environment-dependent
                 :unstable]]
      (is (true? (sut/valid-tool-profile?
                  {:tool/id :tool/X :determinism det}))
          (str det " should be a valid determinism level")))))

;------------------------------------------------------------------------------ Layer 1
;; DetectorConfig schema

(deftest valid-detector-config-test
  (testing "empty map is valid (all fields optional)"
    (is (true? (sut/valid-detector-config? {}))))

  (testing "config with valid directive + params is valid"
    (is (true? (sut/valid-detector-config?
                {:config/directive :tune
                 :config/params    {:window-size 10}}))))

  (testing "each valid directive keyword is accepted"
    (doseq [d [:inherit :disable :enable :tune]]
      (is (true? (sut/valid-detector-config? {:config/directive d}))
          (str d " should be valid"))))

  (testing "invalid directive fails"
    (is (false? (sut/valid-detector-config? {:config/directive :overwrite})))))
