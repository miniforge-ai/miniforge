;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-failures-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit :as audit]
            [ai.miniforge.phase-opsv.kubernetes-load-start-execution-fault-fixtures :as faults]
            [ai.miniforge.phase-opsv.kubernetes-load-start-execution-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-interrupted! [cleanup-failed? failure fixture]
  (let [revoke grant/revoke-stored!]
    (with-redefs [audit/record! (partial faults/interrupt-with-unavailable-clock! fixture failure)
                  grant/revoke-stored! (if cleanup-failed? (constantly (faults/refusal)) revoke)]
      (fixtures/execute fixture))))

(defn- ^{:stratum 0} check-invalid [transform fixture]
  (let [[result calls] (fixtures/execute (transform fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} cancelled [fixture]
  (swap! (:host/context fixture) assoc :execution/status :cancelled)
  fixture)

(defn- ^{:stratum 0} check-registration [transform fixture]
  (let [register grant/register!
        changed #(transform (apply register %&))
        [result calls] (with-redefs [grant/register! changed] (fixtures/execute fixture))
        id (get-in result [:anomaly/data :grant/id])]
    (faults/check-fenced fixture result)
    (is (uuid? id))
    (is (some? (:grant/revoked-at (grant/current (get-in fixture [:host/runtime :authority-directory]) id))))
    (is (empty? (fixtures/mutations calls)))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-unknown [fixture]
  (let [[result calls] (fixtures/execute-response fixture (wire/failed-future (java.io.IOException.)) (constantly nil))
        record (get-in result [:anomaly/data :effect/transaction])]
    (faults/check-fenced fixture result)
    (is (= :unknown-outcome (:effect/state record)))
    (is (= record (effect/read-record (get-in fixture [:host/runtime :effects-directory]) (:effect/id record))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= [:proposed :committing :unknown-outcome] (mapv :opsv/effect-state (fixtures/dispositions fixture))))))

(defn- ^{:stratum 0} check-pre-audit [fixture]
  (let [[result calls] (with-redefs [artifact/publish! (constantly (faults/refusal))] (fixtures/execute fixture))]
    (faults/check-fenced fixture result)
    (is (= :proposed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (empty? (fixtures/mutations calls)))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-post-audit [fixture]
  (let [publish artifact/publish!
        [result calls] (with-redefs [artifact/publish! (partial faults/fail-outcome! publish)] (fixtures/execute fixture))
        record (get-in result [:anomaly/data :effect/transaction])]
    (faults/check-fenced fixture result)
    (is (= :succeeded (:effect/state record)))
    (is (= record (effect/read-record (get-in fixture [:host/runtime :effects-directory]) (:effect/id record))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= [:proposed :committing] (mapv :opsv/effect-state (fixtures/dispositions fixture))))))

(defn- ^{:stratum 0} check-late-publication [fixture]
  (let [publish artifact/publish!
        [result calls] (with-redefs [artifact/publish! (partial faults/change-after-readiness! fixture publish)]
                         (fixtures/execute fixture))]
    (faults/check-fenced fixture result)
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (empty? (fixtures/mutations calls)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-interrupted [cleanup-failed? {:keys [host/runtime request] :as fixture}]
  (let [failure (InterruptedException.)
        [caught flagged?] (wire/interrupted-result
                           (partial execute-interrupted! cleanup-failed? failure fixture))
        record (effect/read-record (:effects-directory runtime) (:effect/id request))
        current (grant/current (:authority-directory runtime) (:effect/grant-id record))]
    (is (identical? failure caught))
    (is flagged?)
    (is (= cleanup-failed? (nil? (:grant/revoked-at current))))
    (is (= cleanup-failed? (boolean (some (comp anomaly/anomaly? ex-data) (.getSuppressed ^Throwable caught)))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(deftest ^{:stratum 1} closed-runtime-input-and-running-context-are-required-before-any-provider-read-test
  (doseq [transform [#(assoc-in % [:host/runtime :serialized-allow?] true)
                     #(assoc-in % [:request :serialized-authority] true)
                     #(assoc-in % [:host/runtime :current-context] (constantly nil))
                     #(assoc-in % [:host/runtime :current-context] faults/refusal)
                     #(assoc-in % [:host/runtime :current-context] (fn [] (throw (ex-info "injected context failure" (faults/refusal)))))
                     cancelled]]
    (fixtures/with-startable (partial check-invalid transform))))

(deftest ^{:stratum 1} unconfirmed-registration-cannot-substitute-an-issued-pin-test
  (doseq [transform [(constantly nil) #(assoc % :grant/id (random-uuid))]]
    (fixtures/with-startable (partial check-registration transform))))

(deftest ^{:stratum 1} unknown-provider-outcome-is-durable-audited-fenced-and-not-retried-test
  (fixtures/with-startable check-unknown))

(deftest ^{:stratum 1} failed-proposal-audit-prevents-native-gate-release-test
  (fixtures/with-startable check-pre-audit))

(deftest ^{:stratum 1} failed-outcome-audit-preserves-actual-success-without-reporting-runtime-success-test
  (fixtures/with-startable check-post-audit))

(deftest ^{:stratum 1} changed-policy-during-readiness-publication-refuses-the-already-prepared-patch-test
  (fixtures/with-startable check-late-publication))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interruption-after-registration-revokes-exact-grant-and-preserves-independent-cleanup-failure-test
  (doseq [cleanup-failed? [false true]] (fixtures/with-startable (partial check-interrupted cleanup-failed?))))

(comment
  (fixtures/with-startable check-unknown))
