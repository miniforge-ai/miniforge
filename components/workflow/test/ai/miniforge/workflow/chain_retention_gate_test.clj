;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-gate-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute [calls definition _input _opts]
  (swap! calls conj [:execute (:chain/run-record definition)])
  (:chain/run-record definition))

(defn- ^{:stratum 0} publish [calls _directory value]
  (swap! calls conj [:publish value])
  value)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} retention-finishes-before-the-executor-starts
  (let [calls (atom []) definition (support/definition :retained "1.0.0")]
    (with-redefs [artifact/publish! (partial publish calls) chain/run-chain (partial execute calls)]
      (let [result (workflow/run-chain definition {} {:chain/retention-directory "/controlled"})
            [_ [_ invocation]] @calls]
        (is (= [:publish :publish :execute] (mapv first @calls)))
        (is (= result (get-in invocation [:artifact/content :chain/record])))
        (is (= (:chain/run-id result) (:artifact/id invocation)))
        (is (= (:chain/steps definition) (get-in result [:chain/definition-snapshot :chain/steps])))))))

(deftest ^{:stratum 1} unconfirmed-retention-prevents-executor-start
  (let [calls (atom []) failure (anomaly/anomaly :unavailable "Unconfirmed retention" {})]
    (with-redefs [artifact/publish! (constantly failure) chain/run-chain (partial execute calls)]
      (is (= failure (workflow/run-chain (support/definition :retained "1.0.0") {}
                                         {:chain/retention-directory "/controlled"})))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} invalid-or-unsafe-admission-does-not-publish-or-execute
  (let [calls (atom []) definition (support/definition :retained "1.0.0")]
    (with-redefs [artifact/publish! (partial publish calls) chain/run-chain (partial execute calls)]
      (doseq [invalid [(dissoc definition :chain/version)
                       (assoc definition :chain/description "Bearer abcdefghijklmnopqrstuvwxyz0123456789")]]
        (is (anomaly/anomaly? (workflow/run-chain invalid {} {:chain/retention-directory "/controlled"}))))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} an-invalid-present-directory-never-falls-back-to-legacy
  (let [calls (atom [])]
    (with-redefs [chain/run-chain (partial execute calls)]
      (doseq [directory [nil "" 42]]
        (is (anomaly/anomaly? (workflow/run-chain (support/definition :retained "1.0.0") {}
                                                  {:chain/retention-directory directory}))))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} absent-directory-preserves-the-explicit-legacy-path
  (let [calls (atom [])]
    (with-redefs [artifact/publish! (partial publish calls) chain/run-chain (partial execute calls)]
      (let [result (workflow/run-chain (support/definition :legacy "1.0.0") {} {})]
        (is (= [:execute] (mapv first @calls)))
        (is (uuid? (:chain/run-id result)))))))

(comment
  ::durable-admission-before-execution)
