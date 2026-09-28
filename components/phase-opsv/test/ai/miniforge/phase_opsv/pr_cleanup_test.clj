;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-cleanup-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} proposal-failure-revokes-newly-issued-authority-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        refused (anomaly/anomaly :unavailable "proposal store failed" {})
        result (with-redefs [actuation/propose-pr! (constantly refused)] (phase/actuate ctx))
        issued (grant/current (:authority-directory runtime) (get-in result [:anomaly/data :grant/id]))]
    (is (= (:anomaly/message refused) (:anomaly/message result)))
    (is (= :revocation/superseded (:grant/revocation-reason issued)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} uncertain-registration-revokes-any-written-authority-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        register grant/register!
        result (with-redefs [grant/register! (fn [directory issued]
                                              (register directory issued)
                                              (anomaly/anomaly :unavailable "registration unconfirmed" {}))]
                 (phase/actuate ctx))
        issued (grant/current (:authority-directory runtime) (get-in result [:anomaly/data :grant/id]))]
    (is (= :unavailable (:anomaly/type result)))
    (is (= :revocation/superseded (:grant/revocation-reason issued)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} cleanup-failure-preserves-both-errors-and-authority-identity-test
  (let [{:keys [ctx calls]} (fixture/setup)
        cleanup (anomaly/anomaly :unavailable "revocation unconfirmed" {})
        result (with-redefs [actuation/propose-pr! (constantly (anomaly/anomaly :fault "proposal failed" {}))
                            grant/revoke-stored! (constantly cleanup)]
                 (phase/actuate ctx))]
    (is (= :fault (:anomaly/type result)))
    (is (uuid? (get-in result [:anomaly/data :grant/id])))
    (is (= cleanup (get-in result [:anomaly/data :grant/revocation-failure])))
    (is (empty? @calls))))

(comment
  (fixture/setup))
