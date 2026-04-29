;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.dag-executor.execution-plan-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.execution-plan :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- valid-mount
  [& {:as overrides}]
  (merge {:host-path      "/host/workspace"
          :container-path "/work"
          :read-only?     false}
         overrides))

(defn- valid-plan
  "Build a minimal valid plan with optional overrides."
  [& {:as overrides}]
  (merge {:image-digest    "sha256:abc123"
          :command         ["bb" "run" "test"]
          :mounts          [(valid-mount)]
          :env             {"HOME" "/root" "CI" "true"}
          :secrets-refs    ["gh-token"]
          :network-profile :standard
          :time-limit-ms   60000
          :memory-limit-mb 512
          :trust-level     :trusted}
         overrides))

;------------------------------------------------------------------------------ Layer 1
;; Constant exposure — trust levels and network profiles

(deftest trust-levels-test
  (testing "trust-levels exposes the documented closed set"
    (is (= #{:untrusted :trusted :privileged} sut/trust-levels))))

(deftest network-profiles-test
  (testing "network-profiles exposes the documented closed set"
    (is (= #{:none :restricted :standard :full} sut/network-profiles))))

;------------------------------------------------------------------------------ Layer 1
;; validate-plan — happy path

(deftest validate-plan-accepts-fully-populated-plan-test
  (testing "A plan with every key correctly typed validates"
    (is (= {:valid? true} (sut/validate-plan (valid-plan))))))

(deftest validate-plan-accepts-empty-collections-test
  (testing "Empty mounts / empty env / empty secrets-refs still validate"
    (is (= {:valid? true}
           (sut/validate-plan (valid-plan :mounts       []
                                          :env          {}
                                          :secrets-refs []))))))

(deftest validate-plan-each-trust-level-and-network-profile-test
  (testing "Every trust-level and network-profile keyword validates"
    (doseq [t sut/trust-levels]
      (is (= {:valid? true}
             (sut/validate-plan (valid-plan :trust-level t)))
          (str ":trust-level " t " should be accepted")))
    (doseq [n sut/network-profiles]
      (is (= {:valid? true}
             (sut/validate-plan (valid-plan :network-profile n)))
          (str ":network-profile " n " should be accepted")))))

;------------------------------------------------------------------------------ Layer 1
;; validate-plan — failure paths

(deftest validate-plan-rejects-missing-required-keys-test
  (testing "Removing :image-digest fails validation"
    (let [{:keys [valid? errors]} (sut/validate-plan (dissoc (valid-plan) :image-digest))]
      (is (false? valid?))
      (is (some? errors)))))

(deftest validate-plan-rejects-bad-trust-level-test
  (testing "An unknown trust-level fails validation"
    (let [{:keys [valid? errors]} (sut/validate-plan (valid-plan :trust-level :rogue))]
      (is (false? valid?))
      (is (some? errors)))))

(deftest validate-plan-rejects-bad-network-profile-test
  (testing "An unknown network-profile fails validation"
    (let [{:keys [valid? errors]} (sut/validate-plan (valid-plan :network-profile :open))]
      (is (false? valid?))
      (is (some? errors)))))

(deftest validate-plan-rejects-string-command-test
  (testing ":command must be a vector of strings, not a single string"
    (is (false? (:valid? (sut/validate-plan (valid-plan :command "bb run test")))))))

(deftest validate-plan-rejects-non-string-env-values-test
  (testing ":env must be string→string"
    (is (false? (:valid? (sut/validate-plan (valid-plan :env {"PORT" 8080})))))))

(deftest validate-plan-rejects-mount-with-missing-fields-test
  (testing "A mount missing :read-only? fails validation"
    (let [bad-mount (dissoc (valid-mount) :read-only?)
          {:keys [valid?]} (sut/validate-plan (valid-plan :mounts [bad-mount]))]
      (is (false? valid?)))))

(deftest validate-plan-rejects-non-int-limits-test
  (testing ":time-limit-ms and :memory-limit-mb must be ints"
    (is (false? (:valid? (sut/validate-plan (valid-plan :time-limit-ms   "60000")))))
    (is (false? (:valid? (sut/validate-plan (valid-plan :memory-limit-mb 1.5)))))))

;------------------------------------------------------------------------------ Layer 2
;; create-execution-plan — round-trip and throw paths

(deftest create-execution-plan-returns-plan-unchanged-test
  (testing "A valid plan is returned unchanged (identity, no normalization)"
    (let [plan (valid-plan)]
      (is (= plan (sut/create-execution-plan plan))))))

(deftest create-execution-plan-throws-anomaly-on-invalid-input-test
  (testing "create-execution-plan throws ExceptionInfo for invalid input,
            with an :errors key in the ex-data and an :anomalies/incorrect category"
    (let [thrown (try
                   (sut/create-execution-plan {:trust-level :rogue})
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (let [data (ex-data thrown)
            anomaly (or (:anomaly data) data)]
        (is (some? (:errors data)) "ex-data must carry :errors")
        ;; throw-anomaly! pattern wraps the data under an anomaly category.
        (is (or (= :anomalies/incorrect (:anomaly/category anomaly))
                (= :anomalies/incorrect (:anomaly/category data))))))))

(deftest create-execution-plan-error-includes-original-plan-test
  (testing "ex-data carries the offending plan map under :plan"
    (let [bad {:trust-level :rogue :command "wrong"}
          thrown (try
                   (sut/create-execution-plan bad)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= bad (:plan (ex-data thrown)))))))
