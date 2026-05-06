;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.tool-profile-test
  "Tests for the tool-profile registration API.

   The API is registration-based — components contribute profiles via
   register!. There is no built-in EDN-driven default registry."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.tool-profile :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories

(defn- make-profile
  "Build a valid ToolProfile for tests."
  [tool-id determinism & {:as opts}]
  (merge {:tool/id tool-id :determinism determinism} opts))

(defn- seeded-registry
  "Build a fresh registry pre-populated with three test profiles."
  []
  (let [reg (sut/make-registry)]
    (sut/register! reg (make-profile :tool/Read :stable-with-resource-version
                                     :anomaly/categories #{:anomalies.agent/tool-loop}
                                     :timeout-ms 30000))
    (sut/register! reg (make-profile :tool/Bash :environment-dependent
                                     :anomaly/categories #{:anomalies.agent/repeated-failure}
                                     :timeout-ms 120000))
    (sut/register! reg (make-profile :tool/WebSearch :unstable))
    reg))

;------------------------------------------------------------------------------ Layer 1
;; make-registry

(deftest make-registry-test
  (testing "make-registry returns an atom seeded with empty map"
    (let [reg (sut/make-registry)]
      (is (instance? clojure.lang.IDeref reg))
      (is (= {} @reg)))))

;------------------------------------------------------------------------------ Layer 1
;; register! / unregister!

(deftest register!-test
  (testing "register! adds the profile keyed by :tool/id"
    (let [reg (sut/make-registry)
          profile (make-profile :tool/Read :stable-with-resource-version)]
      (sut/register! reg profile)
      (is (= profile (sut/lookup :tool/Read reg)))))

  (testing "register! overwrites an existing profile"
    (let [reg (sut/make-registry)]
      (sut/register! reg (make-profile :tool/Read :stable-ish))
      (sut/register! reg (make-profile :tool/Read :stable-with-resource-version))
      (is (= :stable-with-resource-version
             (sut/determinism-of :tool/Read reg)))))

  (testing "register! throws on profile missing :tool/id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/register! (sut/make-registry)
                                {:determinism :unstable}))))

  (testing "register! throws on profile failing schema validation"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/register! (sut/make-registry)
                                {:tool/id :tool/Bad
                                 :determinism :NOT_A_VALID_LEVEL})))))

(deftest unregister!-test
  (testing "unregister! removes the profile"
    (let [reg (seeded-registry)]
      (sut/unregister! reg :tool/Read)
      (is (nil? (sut/lookup :tool/Read reg)))
      (is (= 2 (count @reg)))))

  (testing "unregister! of a missing tool-id is a no-op"
    (let [reg (seeded-registry)]
      (sut/unregister! reg :tool/DoesNotExist)
      (is (= 3 (count @reg))))))

;------------------------------------------------------------------------------ Layer 1
;; lookup

(deftest lookup-test
  (testing "lookup returns the profile for a registered tool"
    (let [reg (seeded-registry)
          profile (sut/lookup :tool/Read reg)]
      (is (map? profile))
      (is (= :tool/Read (:tool/id profile)))
      (is (= :stable-with-resource-version (:determinism profile)))))

  (testing "lookup returns nil for an unregistered tool"
    (is (nil? (sut/lookup :tool/Unknown (seeded-registry)))))

  (testing "lookup accepts an unwrapped registry map (not just an atom)"
    (let [reg (seeded-registry)
          unwrapped @reg]
      (is (= (sut/lookup :tool/Read reg)
             (sut/lookup :tool/Read unwrapped))))))

;------------------------------------------------------------------------------ Layer 1
;; determinism-of

(deftest determinism-of-test
  (testing "returns the registered determinism level"
    (let [reg (seeded-registry)]
      (is (= :stable-with-resource-version (sut/determinism-of :tool/Read reg)))
      (is (= :environment-dependent       (sut/determinism-of :tool/Bash reg)))
      (is (= :unstable                    (sut/determinism-of :tool/WebSearch reg)))))

  (testing "returns :unstable for unknown tools — safe heuristic-only default"
    (is (= :unstable (sut/determinism-of :tool/UnknownToolXyz (seeded-registry))))))

;------------------------------------------------------------------------------ Layer 1
;; categories-of

(deftest categories-of-test
  (testing "returns the registered category set"
    (let [reg (seeded-registry)]
      (is (= #{:anomalies.agent/tool-loop}
             (sut/categories-of :tool/Read reg)))
      (is (= #{:anomalies.agent/repeated-failure}
             (sut/categories-of :tool/Bash reg)))))

  (testing "returns #{} for tool with no :anomaly/categories field"
    (is (= #{} (sut/categories-of :tool/WebSearch (seeded-registry)))))

  (testing "returns #{} for unknown tools"
    (is (= #{} (sut/categories-of :tool/Unknown (seeded-registry))))))

;------------------------------------------------------------------------------ Layer 1
;; all-tool-ids

(deftest all-tool-ids-test
  (testing "returns sorted vector of registered tool-ids"
    (let [reg (seeded-registry)
          ids (sut/all-tool-ids reg)]
      (is (vector? ids))
      (is (= 3 (count ids)))
      (is (= ids (vec (sort ids))))
      (is (= #{:tool/Read :tool/Bash :tool/WebSearch} (set ids))))))

;------------------------------------------------------------------------------ Layer 1
;; validate-all

(deftest validate-all-test
  (testing "all registered profiles report :valid? true"
    (let [results (sut/validate-all (seeded-registry))]
      (is (every? :valid? (vals results)))
      (is (every? nil? (map :errors (vals results))))))

  (testing "validate-all surfaces ToolProfile errors via the right humanizer"
    ;; Bypass register!'s validation by constructing the registry map
    ;; directly and feeding it to validate-all.
    (let [bad-reg (atom {:tool/Bad {:tool/id :tool/Bad
                                    :determinism :NOT_A_VALID_LEVEL}})
          results (sut/validate-all bad-reg)]
      (is (false? (get-in results [:tool/Bad :valid?])))
      (is (some? (get-in results [:tool/Bad :errors]))
          "errors must include humanized schema feedback"))))
