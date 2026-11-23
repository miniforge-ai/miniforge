;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-audit-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-proposed [{:keys [ctx runtime decision recovery/record] :as fixture}]
  (let [before (fixtures/assembly fixture)
        result (fixtures/audit fixture)
        replayed (fixtures/audit fixture)
        audit (fixtures/dispositions fixture)
        retained (fixtures/assembly fixture)
        event (first audit)
        references (set/difference (:opsv/artifact-refs retained) (:opsv/artifact-refs before))]
    (is (= :proposed (:effect/state result)))
    (is (nil? (:effect/authority record)))
    (is (= result replayed record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= [:proposed] (mapv :opsv/effect-state audit)))
    (is (= decision (:opsv/decision-envelope event)))
    (is (= {} (:opsv/effect-observed event)))
    (is (contains? (:opsv/grant-refs retained) (:effect/grant-id record)))
    (is (contains? (:opsv/governed-effects retained) (:opsv/governed-effect event)))
    (is (contains? (:opsv/event-refs retained) (:event/id event)))
    (is (= 1 (count references)))
    (doseq [id references]
      (let [value (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) id)]
        (is (= :opsv-verification-report (:artifact/type value)))
        (is (= :native-effect-disposition (get-in value [:artifact/metadata :opsv/material-kind])))
        (is (some? (get-in value [:artifact/metadata :evidence/content-protection])))
        (is (protected/valid? ctx value))))))

(defn- ^{:stratum 0} check-confirmed [fixture]
  (let [_proposed (fixtures/audit fixture)
        [committed calls] (fixtures/success! fixture)
        recorded (fixtures/audit fixture)
        audit (fixtures/dispositions fixture)]
    (is (= :succeeded (:effect/state committed)))
    (is (= committed recorded))
    (is (= 1 (count calls)))
    (is (= [:proposed :succeeded] (mapv :opsv/effect-state audit)))
    (is (not-any? #(contains? (:opsv/effect-observed (last audit)) %)
                  [:recovery/verified? :load/quiescence :load/absent? :verification/passed?]))))

(defn- ^{:stratum 0} check-unknown [fixture]
  (let [[committed calls] (fixtures/commit! fixture (wire/failed-future (java.io.IOException.)))
        recorded (fixtures/audit fixture)
        audit (fixtures/dispositions fixture)]
    (is (= :unknown-outcome (:effect/state committed)))
    (is (= committed recorded))
    (is (= 1 (count calls)))
    (is (= [:unknown-outcome] (mapv :opsv/effect-state audit)))
    (is (= {} (:opsv/effect-observed (first audit))))))

(defn- ^{:stratum 0} check-join-refusal [fixture]
  (doseq [changed [(assoc-in fixture [:decision :envelope/id] (random-uuid))
                   (assoc-in fixture [:decision :envelope/decision] :deny)
                   (assoc-in fixture [:ctx :execution/id] (random-uuid))
                   (update fixture :ctx dissoc :event-stream)]]
    (let [before (fixtures/assembly fixture)
          result (fixtures/audit changed)]
      (is (anomaly/anomaly? result))
      (is (= before (fixtures/assembly fixture)))
      (is (empty? (fixtures/dispositions fixture))))))

(defn- ^{:stratum 0} check-denied-commit [{:keys [recovery/issued-id] :as fixture}]
  (grant/revoke-stored! (:recovery/grants-directory fixture) issued-id :revocation/operator clock/now)
  (let [[committed calls] (fixtures/success! fixture)
        result (fixtures/audit fixture)]
    (is (= :failed (:effect/state committed)))
    (is (nil? (:effect/authority committed)))
    (is (= committed result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-unenforced [{:keys [runtime recovery/record] :as fixture}]
  (let [changed (assoc record :effect/authority :unenforced)
        _stored (transactions/transition! (:effects-directory runtime) record changed)
        result (fixtures/audit fixture)]
    (is (anomaly/anomaly? result))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-publication-failure [fixture]
  (let [[committed _calls] (fixtures/success! fixture)
        ctx (:ctx fixture)
        _quiesced (events/quiesce! (:event-stream ctx) {:workflow-id (:execution/id ctx)})
        result (fixtures/audit fixture)]
    (is (= :succeeded (:effect/state committed)))
    (is (anomaly/anomaly? result))
    (is (= committed (get-in result [:anomaly/data :effect/transaction])))
    (is (= :opsv.actuation/disposition (get-in result [:anomaly/data :opsv/disposition-event :event/type])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} proposed-real-native-record-is-audited-before-authority-is-claimed-with-idempotent-protected-lineage-test
  (fixtures/with-proposed check-proposed))

(deftest ^{:stratum 1} confirmed-native-result-is-reread-and-published-without-recovery-claims-test
  (fixtures/with-proposed check-confirmed))

(deftest ^{:stratum 1} uncertain-native-outcome-is-retained-without-success-references-test
  (fixtures/with-proposed check-unknown))

(deftest ^{:stratum 1} mismatched-decisions-workflows-or-missing-journals-refuse-before-publication-test
  (fixtures/with-proposed check-join-refusal))

(deftest ^{:stratum 1} refused-current-grant-is-auditable-without-inventing-claimed-authority-test
  (fixtures/with-proposed check-denied-commit))

(deftest ^{:stratum 1} unenforced-records-never-become-native-governed-dispositions-test
  (fixtures/with-proposed check-unenforced))

(deftest ^{:stratum 1} failed-publication-preserves-durable-outcome-and-pending-disposition-for-recovery-test
  (fixtures/with-proposed check-publication-failure))

(comment
  (fixtures/with-proposed check-proposed))
