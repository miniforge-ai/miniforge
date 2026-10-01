;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-compliance-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.evidence-bundle.publication-compliance :as compliance]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private synthetic-secret "AKIAIOSFODNN7EXAMPLE")

(defn- ^{:stratum 0} candidate [& {:as overrides}]
  (merge {:compliance/sensitive-data false
          :compliance/pii-handling :none
          :evidence/contains-pii? false}
         overrides))

(defn- ^{:stratum 0} finding [kind]
  {:finding/type kind})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recorded [kind]
  (candidate :compliance/sensitive-findings [(finding kind)]))

(defn- ^{:stratum 1} contaminated-finding [placement]
  (let [base (finding :aws-access-key)
        secret {:note synthetic-secret}]
    (case placement
      :field (assoc base :finding/value synthetic-secret)
      :metadata (with-meta base secret)
      :nested (assoc base :finding/context [(with-meta [] secret)]))))

(deftest ^{:stratum 1} fresh-secrets-are-redacted-and-declared
  (doseq [secret [synthetic-secret "000-00-0000"]]
    (let [input (candidate :description secret)
          prepared (compliance/prepare input)]
      (is (redaction/clean? prepared))
      (is (true? (:compliance/sensitive-data prepared)))
      (is (= :redacted (:compliance/pii-handling prepared)))
      (is (compliance/accurate-declarations? prepared))
      (is (= secret (:description input))))))

(deftest ^{:stratum 1} innocuous-content-retains-none-treatment
  (let [prepared (compliance/prepare (candidate))]
    (is (false? (:compliance/sensitive-data prepared)))
    (is (= :none (:compliance/pii-handling prepared)))
    (is (compliance/accurate-declarations? prepared))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} recorded-secrets-require-protected-treatment
  (doseq [kind [:aws-access-key :embedded-secret :ssn :payment-card]]
    (let [prepared (compliance/prepare (recorded kind))]
      (is (= :redacted (:compliance/pii-handling prepared)))
      (is (compliance/accurate-declarations? prepared))
      (is (false? (compliance/accurate-declarations?
                    (assoc prepared :compliance/pii-handling :none))))
      (is (false? (compliance/accurate-declarations?
                    (assoc prepared :compliance/sensitive-data false))))
      (is (compliance/accurate-declarations?
            (assoc prepared :compliance/pii-handling :encrypted))))))

(deftest ^{:stratum 2} recorded-pii-requires-personal-information-declaration
  (doseq [kind [:email :ssn :payment-card]]
    (let [prepared (compliance/prepare (recorded kind))]
      (is (true? (:evidence/contains-pii? prepared)))
      (is (compliance/accurate-declarations? prepared))
      (is (false? (compliance/accurate-declarations?
                    (assoc prepared :evidence/contains-pii? false)))))))

(deftest ^{:stratum 2} recorded-findings-cannot-reintroduce-sensitive-values
  (doseq [placement [:field :metadata :nested]]
    (let [input (candidate :compliance/sensitive-findings [(contaminated-finding placement)])
          prepared (compliance/prepare input)]
      (is (not (redaction/clean? input)))
      (is (redaction/clean? prepared))
      (is (= :redacted (:compliance/pii-handling prepared)))
      (is (compliance/accurate-declarations? prepared)))))

(deftest ^{:stratum 2} encryption-declaration-survives-only-without-fresh-redaction
  (let [encrypted (assoc (recorded :payment-card) :compliance/pii-handling :encrypted)]
    (is (= :encrypted (:compliance/pii-handling (compliance/prepare encrypted))))
    (is (= :redacted (:compliance/pii-handling
                      (compliance/prepare (assoc encrypted :description synthetic-secret)))))))
