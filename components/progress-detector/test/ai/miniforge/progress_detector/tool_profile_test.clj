;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.tool-profile-test
  "Tests for the EDN-driven tool-profile registry."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.tool-profile :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; In-memory registry for unit tests (no classpath dependency)

(def ^:private test-registry
  {:tool/bash
   {:detector/kind      :detector.kind/shell
    :determinism        :volatile
    :anomaly/categories #{:anomaly.category/stall :anomaly.category/error}
    :timeout-ms         120000}

   :tool/write
   {:detector/kind      :detector.kind/fs-mutation
    :determinism        :stable
    :anomaly/categories #{:anomaly.category/stall}
    :timeout-ms         30000}

   :tool/agent
   {:detector/kind      :detector.kind/llm
    :determinism        :nondeterministic
    :anomaly/categories #{:anomaly.category/cost-spike :anomaly.category/stall}
    :timeout-ms         600000}})

;------------------------------------------------------------------------------ Layer 1
;; load-registry

(deftest load-registry-from-classpath-test
  (testing "load-registry returns a non-empty map from the bundled EDN"
    (let [reg (sut/load-registry)]
      (is (map? reg))
      (is (pos? (count reg)) "registry should not be empty")
      (is (contains? reg :tool/bash)  ":tool/bash should be present")
      (is (contains? reg :tool/agent) ":tool/agent should be present"))))

(deftest load-registry-bad-path-test
  (testing "load-registry throws on missing resource"
    (is (thrown? Exception (sut/load-registry "no/such/resource.edn")))))

;------------------------------------------------------------------------------ Layer 1
;; lookup

(deftest lookup-test
  (testing "lookup returns the profile for a known tool"
    (let [profile (sut/lookup :tool/bash test-registry)]
      (is (map? profile))
      (is (= :detector.kind/shell (:detector/kind profile)))
      (is (= :volatile (:determinism profile)))))

  (testing "lookup returns nil for an unknown tool"
    (is (nil? (sut/lookup :tool/unknown test-registry)))))

;------------------------------------------------------------------------------ Layer 1
;; determinism-of

(deftest determinism-of-test
  (testing "returns :volatile for volatile tool"
    (is (= :volatile (sut/determinism-of :tool/bash test-registry))))

  (testing "returns :stable for stable tool"
    (is (= :stable (sut/determinism-of :tool/write test-registry))))

  (testing "returns :nondeterministic for LLM tool"
    (is (= :nondeterministic (sut/determinism-of :tool/agent test-registry))))

  (testing "returns :volatile default for unknown tool"
    (is (= :volatile (sut/determinism-of :tool/unknown-xyz test-registry)))))

;------------------------------------------------------------------------------ Layer 1
;; categories-of

(deftest categories-of-test
  (testing "returns categories set for known tool"
    (let [cats (sut/categories-of :tool/bash test-registry)]
      (is (set? cats))
      (is (contains? cats :anomaly.category/stall))
      (is (contains? cats :anomaly.category/error))))

  (testing "returns empty set for unknown tool"
    (is (= #{} (sut/categories-of :tool/unknown test-registry)))))

;------------------------------------------------------------------------------ Layer 1
;; detector-kind-of

(deftest detector-kind-of-test
  (testing "returns shell kind for bash"
    (is (= :detector.kind/shell (sut/detector-kind-of :tool/bash test-registry))))

  (testing "returns fs-mutation kind for write"
    (is (= :detector.kind/fs-mutation (sut/detector-kind-of :tool/write test-registry))))

  (testing "returns :detector.kind/shell default for unknown tool"
    (is (= :detector.kind/shell (sut/detector-kind-of :tool/unknown test-registry)))))

;------------------------------------------------------------------------------ Layer 1
;; all-tool-ids

(deftest all-tool-ids-test
  (testing "returns sorted vector of all keys"
    (let [ids (sut/all-tool-ids test-registry)]
      (is (vector? ids))
      (is (= 3 (count ids)))
      (is (= ids (vec (sort ids))) "result must be sorted")))

  (testing "contains all expected keys"
    (let [ids (set (sut/all-tool-ids test-registry))]
      (is (contains? ids :tool/bash))
      (is (contains? ids :tool/write))
      (is (contains? ids :tool/agent)))))

;------------------------------------------------------------------------------ Layer 1
;; register / unregister

(deftest register-test
  (testing "adds a new profile and does not mutate original"
    (let [new-profile {:detector/kind :detector.kind/network
                       :determinism   :volatile}
          new-reg (sut/register test-registry :tool/fetch new-profile)]
      (is (= 4 (count new-reg)) "new registry should have 4 entries")
      (is (= 3 (count test-registry)) "original registry should be unchanged")
      (is (= new-profile (sut/lookup :tool/fetch new-reg)))))

  (testing "overwrites existing profile"
    (let [override {:detector/kind :detector.kind/composite
                    :determinism   :deterministic}
          new-reg  (sut/register test-registry :tool/bash override)]
      (is (= override (sut/lookup :tool/bash new-reg))))))

(deftest unregister-test
  (testing "removes a profile"
    (let [new-reg (sut/unregister test-registry :tool/bash)]
      (is (nil? (sut/lookup :tool/bash new-reg)))
      (is (= 2 (count new-reg)))))

  (testing "unregister of non-existent key is a no-op"
    (let [new-reg (sut/unregister test-registry :tool/does-not-exist)]
      (is (= 3 (count new-reg))))))

;------------------------------------------------------------------------------ Layer 1
;; validate-all

(deftest validate-all-test
  (testing "all profiles in test-registry are valid"
    (let [results (sut/validate-all test-registry)]
      (is (every? :valid? (vals results))
          "all profiles should be valid against ToolProfile schema")))

  (testing "invalid profiles are flagged"
    (let [bad-reg   (assoc test-registry :tool/bad {:determinism :???})
          results   (sut/validate-all bad-reg)]
      (is (false? (get-in results [:tool/bad :valid?]))
          "bad profile should not be valid"))))
