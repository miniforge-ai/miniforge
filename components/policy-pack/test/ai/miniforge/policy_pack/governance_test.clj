;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.policy-pack.governance-test
  "Tests for governance hardening — trust violations, ordered validation, crypto."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as dep-val]))

;; ============================================================================
;; Trust violation detection
;; ============================================================================

(def base-pack
  {:pack/id "base" :pack/trust-level :trusted :pack/authority :authority/instruction
   :pack/extends []})

(def tainted-pack
  {:pack/id "tainted" :pack/trust-level :tainted :pack/authority :authority/data
   :pack/extends []})

(deftest tainted-dependency-violation-test
  (testing "non-tainted pack depending on tainted pack produces violation"
    (let [pack  {:pack/id "consumer" :pack/trust-level :untrusted
                 :pack/authority :authority/data
                 :pack/extends [{:pack-id "tainted"}]}
          by-id {"consumer" pack "tainted" tainted-pack}
          viols (dep-val/detect-trust-violations {} by-id)]
      (is (= 1 (count viols)))
      (is (= :trust-violation (:type (first viols))))))

  (testing "tainted pack depending on tainted pack produces no violation"
    (let [pack  {:pack/id "also-tainted" :pack/trust-level :tainted
                 :pack/authority :authority/data
                 :pack/extends [{:pack-id "tainted"}]}
          by-id {"also-tainted" pack "tainted" tainted-pack}
          viols (dep-val/detect-trust-violations {} by-id)]
      (is (empty? viols)))))

(deftest untrusted-instruction-escalation-test
  (testing "untrusted pack with instruction authority depending on trusted produces violation"
    (let [pack  {:pack/id "untrusted" :pack/trust-level :untrusted
                 :pack/authority :authority/instruction
                 :pack/extends [{:pack-id "base"}]}
          by-id {"untrusted" pack "base" base-pack}
          viols (dep-val/detect-trust-violations {} by-id)]
      (is (= 1 (count viols)))
      (is (= :trust-violation (:type (first viols))))))

  (testing "untrusted pack with data authority depending on trusted is fine"
    (let [pack  {:pack/id "untrusted" :pack/trust-level :untrusted
                 :pack/authority :authority/data
                 :pack/extends [{:pack-id "base"}]}
          by-id {"untrusted" pack "base" base-pack}
          viols (dep-val/detect-trust-violations {} by-id)]
      (is (empty? viols)))))

(deftest clean-dependencies-test
  (testing "packs with no trust violations return empty"
    (let [by-id {"base" base-pack}
          viols (dep-val/detect-trust-violations {} by-id)]
      (is (empty? viols)))))

;; ============================================================================
;; Ordered validation
;; ============================================================================

(deftest ordered-validation-all-pass-test
  (testing "all layers passing returns passed? true"
    (let [layers [{:layer :L0 :validate-fn (fn [] {:passed? true})}
                  {:layer :L1 :validate-fn (fn [] {:passed? true})}
                  {:layer :L2 :validate-fn (fn [] {:passed? true})}]
          result (core/ordered-validation layers)]
      (is (true? (:passed? result)))
      (is (= 3 (count (:results result))))
      (is (nil? (:short-circuited-at result))))))

(deftest ordered-validation-short-circuit-test
  (testing "L0 failure short-circuits L1 and L2"
    (let [l1-called (atom false)
          layers [{:layer :L0 :validate-fn (fn [] {:passed? false :violations [:syntax-error]})}
                  {:layer :L1 :validate-fn (fn [] (reset! l1-called true) {:passed? true})}]
          result (core/ordered-validation layers)]
      (is (false? (:passed? result)))
      (is (= :L0 (:short-circuited-at result)))
      (is (= 1 (count (:results result))))
      (is (false? @l1-called)))))

(deftest ordered-validation-middle-failure-test
  (testing "L1 failure short-circuits L2 but L0 ran"
    (let [layers [{:layer :L0 :validate-fn (fn [] {:passed? true})}
                  {:layer :L1 :validate-fn (fn [] {:passed? false})}
                  {:layer :L2 :validate-fn (fn [] {:passed? true})}]
          result (core/ordered-validation layers)]
      (is (false? (:passed? result)))
      (is (= :L1 (:short-circuited-at result)))
      (is (= 2 (count (:results result)))))))
