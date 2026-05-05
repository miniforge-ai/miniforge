;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.schema-test
  "Tests for anomaly and observation Malli schemas."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.schema :as sut]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- now [] (java.time.Instant/now))

(defn- valid-anomaly []
  {:anomaly/id          "123e4567-e89b-12d3-a456-426614174000"
   :anomaly/category    :anomaly.category/loop
   :anomaly/class       :anomaly.class/hard
   :anomaly/severity    :anomaly.severity/warning
   :detector/kind       :detector.kind/fs-read
   :anomaly/evidence    [{:evidence/kind :evidence.kind/window
                          :evidence/data {:window-size 5 :unique-calls 1}}]
   :anomaly/detected-at (now)})

(defn- valid-observation []
  {:tool/id   :tool/bash
   :seq       1
   :timestamp (now)
   :tool/duration-ms 150})

(defn- valid-tool-profile []
  {:detector/kind      :detector.kind/shell
   :determinism        :volatile
   :anomaly/categories #{:anomaly.category/stall}
   :timeout-ms         120000})

;; ---------------------------------------------------------------------------
;; Anomaly schema

(deftest valid-anomaly-test
  (testing "valid anomaly passes validation"
    (is (true? (sut/valid-anomaly? (valid-anomaly)))))

  (testing "anomaly with optional description is valid"
    (is (true? (sut/valid-anomaly? (assoc (valid-anomaly)
                                          :anomaly/description "Looping on fs-read")))))

  (testing "anomaly with optional context is valid"
    (is (true? (sut/valid-anomaly? (assoc (valid-anomaly)
                                          :anomaly/context {:tool-id :tool/read :count 5}))))))

(deftest invalid-anomaly-test
  (testing "missing required :anomaly/id fails"
    (is (false? (sut/valid-anomaly? (dissoc (valid-anomaly) :anomaly/id)))))

  (testing "integer :anomaly/id fails"
    (is (false? (sut/valid-anomaly? (assoc (valid-anomaly) :anomaly/id 42)))))

  (testing "invalid :anomaly/category fails"
    (is (false? (sut/valid-anomaly? (assoc (valid-anomaly) :anomaly/category :bad/cat)))))

  (testing "invalid :anomaly/severity fails"
    (is (false? (sut/valid-anomaly? (assoc (valid-anomaly) :anomaly/severity :anomaly.severity/oops)))))

  (testing "invalid :detector/kind fails"
    (is (false? (sut/valid-anomaly? (assoc (valid-anomaly) :detector/kind :detector.kind/unknown)))))

  (testing "missing :anomaly/detected-at fails"
    (is (false? (sut/valid-anomaly? (dissoc (valid-anomaly) :anomaly/detected-at))))))

(deftest explain-anomaly-test
  (testing "returns nil for valid anomaly"
    (is (nil? (sut/explain-anomaly (valid-anomaly)))))

  (testing "returns error map for invalid anomaly"
    (let [errors (sut/explain-anomaly (dissoc (valid-anomaly) :anomaly/id))]
      (is (some? errors)))))

;; ---------------------------------------------------------------------------
;; Observation schema

(deftest valid-observation-test
  (testing "valid observation passes validation"
    (is (true? (sut/valid-observation? (valid-observation)))))

  (testing "observation with optional fields is valid"
    (is (true? (sut/valid-observation?
                (assoc (valid-observation)
                       :tool/input {:cmd "ls"}
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

  (testing "returns error map for invalid observation"
    (is (some? (sut/explain-observation {})))))

;; ---------------------------------------------------------------------------
;; ToolProfile schema

(deftest valid-tool-profile-test
  (testing "valid tool profile passes validation"
    (is (true? (sut/valid-tool-profile? (valid-tool-profile)))))

  (testing "profile without optional fields is valid"
    (is (true? (sut/valid-tool-profile?
                {:detector/kind :detector.kind/llm
                 :determinism   :nondeterministic})))))

(deftest invalid-tool-profile-test
  (testing "missing :detector/kind fails"
    (is (false? (sut/valid-tool-profile? (dissoc (valid-tool-profile) :detector/kind)))))

  (testing "missing :determinism fails"
    (is (false? (sut/valid-tool-profile? (dissoc (valid-tool-profile) :determinism)))))

  (testing "invalid :determinism value fails"
    (is (false? (sut/valid-tool-profile? (assoc (valid-tool-profile) :determinism :random))))))

;; ---------------------------------------------------------------------------
;; DetectorConfig schema

(deftest valid-detector-config-test
  (testing "empty map is valid (all fields optional)"
    (is (true? (sut/valid-detector-config? {}))))

  (testing "config with valid directive is valid"
    (is (true? (sut/valid-detector-config?
                {:config/directive :tune
                 :config/params    {:window-size 10}}))))

  (testing "each valid directive keyword is accepted"
    (doseq [d [:inherit :disable :enable :tune]]
      (is (true? (sut/valid-detector-config? {:config/directive d}))
          (str d " should be valid"))))

  (testing "invalid directive fails"
    (is (false? (sut/valid-detector-config? {:config/directive :overwrite})))))

;; ---------------------------------------------------------------------------
;; Enumeration schemas

(deftest anomaly-category-values-test
  (testing "all expected category keywords are valid"
    (doseq [cat [:anomaly.category/stall
                 :anomaly.category/loop
                 :anomaly.category/regression
                 :anomaly.category/error
                 :anomaly.category/cost-spike
                 :anomaly.category/timeout]]
      (is (true? (sut/valid-anomaly?
                  (assoc (valid-anomaly) :anomaly/category cat)))
          (str cat " should be a valid category")))))

(deftest anomaly-severity-values-test
  (testing "all expected severity keywords are valid"
    (doseq [sev [:anomaly.severity/critical
                 :anomaly.severity/warning
                 :anomaly.severity/info]]
      (is (true? (sut/valid-anomaly?
                  (assoc (valid-anomaly) :anomaly/severity sev)))
          (str sev " should be a valid severity")))))
