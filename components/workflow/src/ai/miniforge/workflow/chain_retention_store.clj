;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-store
  "Acknowledge immutable manifests without owning a second filesystem protocol."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.workflow.chain-retention-record :as record]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} matching [expected actual type key]
  (anomaly/let-ok [value actual
                   expected-digest (artifact/content-digest expected)
                   actual-digest (artifact/content-digest value)]
    (if (and (= expected value) (= expected-digest actual-digest)) expected
        (record/failure type key))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish! [directory value]
  (matching value (artifact/publish! directory value) :unavailable :chain/retention-unconfirmed))

(defn- ^{:stratum 1} verify [expected actual]
  (if (nil? actual) (record/failure :not-found :chain/retention-missing)
      (matching expected actual :conflict :chain/retention-conflict)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist! [directory [definition invocation]]
  (anomaly/let-ok [_definition (publish! directory definition)
                   _invocation (publish! directory invocation)]
    (get-in invocation [:artifact/content :chain/record])))

(defn ^{:stratum 2} recover [directory actual [definition invocation]]
  (anomaly/let-ok [_invocation (verify invocation actual)
                   _definition (verify definition (artifact/read-published directory (:artifact/id definition)))]
    (get-in invocation [:artifact/content :chain/record])))

(comment
  ::durably-acknowledged-admission)
