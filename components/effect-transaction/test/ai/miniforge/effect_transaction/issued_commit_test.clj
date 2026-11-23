;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.issued-commit-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.fixtures :as base]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.issued-commit-fixtures :as fixtures]
            [ai.miniforge.execution-grant.interface :as grant]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} matching-runtime-pin-preserves-current-lookup-clock-and-durable-execution-test
  (let [{:keys [dir issued record events] :as input} (fixtures/setup)
        done (fixtures/commit! input)]
    (is (= :succeeded (:effect/state done)))
    (is (= [[:lookup (:grant/id issued)] :clock [:effect (:effect/id record)]] @events))
    (is (= :granted (:effect/authority done)))
    (is (= done (effect/read-record dir (:effect/id record))))))

(deftest ^{:stratum 0} independently-issued-identical-scope-cannot-replace-the-runtime-pin-test
  (let [{:keys [dir issued current record events] :as input} (fixtures/setup true)
        result (fixtures/commit! input)]
    (is (not= (:grant/id issued) (:grant/id current)))
    (is (= (:grant/scope issued) (:grant/scope current)))
    (is (= :unauthorized (:anomaly/type result)))
    (is (empty? @events))
    (is (= record (effect/read-record dir (:effect/id record))))))

(deftest ^{:stratum 0} malformed-issued-pins-refuse-before-any-runtime-port-test
  (let [{:keys [dir record lookup clock operation events]} (fixtures/setup)]
    (doseq [pin [nil false {} "not-a-uuid"]]
      (is (= :invalid-input (:anomaly/type (effect/commit-current! dir (:effect/id record) pin lookup clock operation)))))
    (is (empty? @events))
    (is (= record (effect/read-record dir (:effect/id record))))))

(deftest ^{:stratum 0} matching-pin-still-enforces-current-revocation-and-expiry-test
  (doseq [reason [:revoked :expired]]
    (let [{:keys [dir current record events] :as input} (fixtures/setup)
          changed (if (= :revoked reason) (grant/revoke current :revocation/operator base/now) current)
          clock (if (= :expired reason) base/much-later base/now)
          done (fixtures/commit! (assoc input :lookup (constantly changed) :clock (constantly clock)))]
      (is (= :failed (:effect/state done)))
      (is (empty? @events))
      (is (= done (effect/read-record dir (:effect/id record)))))))

(deftest ^{:stratum 0} unavailable-current-authority-preserves-the-durable-proposal-test
  (let [{:keys [dir record events] :as input} (fixtures/setup)
        result (fixtures/commit! (assoc input :lookup (constantly nil)))]
    (is (anomaly/anomaly? result))
    (is (empty? @events))
    (is (= record (effect/read-record dir (:effect/id record))))))

(deftest ^{:stratum 0} backend-cannot-substitute-its-own-different-grant-test
  (let [{:keys [dir current record events] :as input} (fixtures/setup)
        other (base/merge-grant {:effect-id (get-in current [:grant/scope :effect/id])})
        result (fixtures/commit! (assoc input :lookup (constantly other)))]
    (is (= :conflict (:anomaly/type result)))
    (is (= [:clock] @events))
    (is (= record (effect/read-record dir (:effect/id record))))))

(deftest ^{:stratum 0} pinned-commit-does-not-reexecute-a-settled-effect-test
  (let [{:keys [events] :as input} (fixtures/setup)]
    (is (= :succeeded (:effect/state (fixtures/commit! input))))
    (let [completed @events]
      (is (anomaly/anomaly? (fixtures/commit! input)))
      (is (= completed @events)))))

(deftest ^{:stratum 0} claim-race-still-refuses-the-losing-pinned-consumer-test
  (let [{:keys [dir current record events] :as input} (fixtures/setup)
        lookup (partial fixtures/competing! dir record current)
        result (fixtures/commit! (assoc input :lookup lookup))]
    (is (= :conflict (:anomaly/type result)))
    (is (= [:clock] @events))
    (is (= :succeeded (:effect/state (effect/read-record dir (:effect/id record)))))))

(comment ::issuance-pin-is-not-a-grant-snapshot-or-a-second-proposal-read)
