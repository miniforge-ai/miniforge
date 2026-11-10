;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-execution-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-execution-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-effect-publication-fault-fixtures :as publication]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replicas]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as trial]
            [ai.miniforge.phase-opsv.prometheus-test-support :as http]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-restored [{:keys [host/runtime] :as fixture}]
  (let [[result calls requests] (fixtures/execute fixture)
        issuance (first requests)]
    (is (not (anomaly/anomaly? result)))
    (is (= :succeeded (:effect/state result)))
    (is (= :restore-baseline (get-in result [:effect/proposal :opsv/experiment :opsv/operation])))
    (is (= replicas/original-count (get-in result [:effect/observed :replicas/count])))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id result))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= :patch (:method (last calls))))
    (is (= 1 (count requests)))
    (is (= (:execution/status @(:host/context fixture)) (:workflow-run/status issuance)))
    (is (= (:opsv/experiment (:effect/proposal result)) (:opsv/experiment issuance)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (not-any? #(contains? (:effect/observed result) %) [:recovery/verified? :slo/passed?]))
    result))

(defn- ^{:stratum 0} check-refused [transform {:keys [host/runtime] :as fixture}]
  (let [[result calls requests] (fixtures/execute (transform fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (empty? requests))
    (is (empty? (forward/dispositions fixture)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-replay [fixture]
  (let [[initial _calls _requests] (fixtures/execute fixture)
        [result calls requests] (fixtures/execute fixture)]
    (is (= :succeeded (:effect/state initial)))
    (is (= :conflict (:anomaly/type result)))
    (is (= initial (get-in result [:anomaly/data :effect/transaction])))
    (is (empty? calls))
    (is (empty? requests))))

(defn- ^{:stratum 0} check-uncertain [{:keys [host/runtime] :as fixture}]
  (let [pending (http/failed-future (java.io.IOException.))
        [result calls _requests] (fixtures/execute-response fixture pending (constantly nil))
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (= :unknown-outcome (:effect/state record)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (some? (:grant/revoked-at (fixtures/current-grant fixture (:effect/grant-id record)))))))

(defn- ^{:stratum 0} check-publication-failure [{:keys [host/runtime] :as fixture}]
  (let [{:keys [result calls artifact]} (publication/execute fixtures/execute :native-baseline-restoration-readiness fixture)
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (anomaly/anomaly? result))
    (is (= :failed (:effect/state record)))
    (is (some? (:artifact/content artifact)))
    (is (empty? (fixtures/mutations calls)))
    (is (some? (:grant/revoked-at (fixtures/current-grant fixture (:effect/grant-id record)))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-late-refusal [change {:keys [host/runtime] :as fixture}]
  (let [armed (atom true)
        change! (partial trial/change! change)
        [result calls _requests] (fixtures/execute fixture (partial forward/on-claim! change! armed))]
    (is (false? @armed))
    (is (anomaly/anomaly? result))
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (empty? (fixtures/mutations calls)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-foreign-runtime [{:keys [directory host/runtime] :as fixture}]
  (let [foreign (str (fs/path directory "unbound-recovery-authority"))
        changed (assoc-in fixture [:host/runtime :authority-directory] foreign)
        [result calls requests] (fixtures/execute changed)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (empty? requests))
    (is (empty? (forward/dispositions fixture)))
    (is (false? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-identities [fixture]
  (let [digest (get-in fixture [:prepared :recovery/hash])
        operations [:stop-load :cleanup-load :restore-baseline]
        identities (mapv (partial phase/recovery-effect-id digest) operations)]
    (is (every? uuid? identities))
    (is (= (count operations) (count (set identities))))
    (is (= identities (mapv (partial phase/recovery-effect-id digest) operations)))
    (doseq [[hash operation] [[nil :restore-baseline] [{} :restore-baseline]
                             [digest :trial-apply] [digest :start-load] [digest nil]]]
      (is (anomaly/anomaly? (phase/recovery-effect-id hash operation))))))

(defn- ^{:stratum 0} check-forward-in-flight [{:keys [host/runtime] :as fixture}]
  (let [[result calls requests] (actuation/at-mutation-boundary! (:fence runtime) (partial fixtures/execute fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? calls))
    (is (empty? requests))
    (is (empty? (forward/dispositions fixture)))
    (is (zero? (:in-flight (actuation/mutation-status (:fence runtime)))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-printer-independent-identities [fixture]
  (let [digest (get-in fixture [:prepared :recovery/hash])
        operations [:stop-load :cleanup-load :restore-baseline]
        identity-for (partial phase/recovery-effect-id digest)
        expected (mapv identity-for operations)
        truncated (binding [*print-length* 1 *print-level* 0] (mapv identity-for operations))
        duplicated (binding [*print-dup* true *print-readably* false] (mapv identity-for operations))]
    (is (= expected truncated duplicated))
    (is (= (count operations) (count (set truncated))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-revoked-forward [fixture]
  (let [stopped (->> fixture (fixtures/stop! :cancelled) fixtures/safe-mode!)
        result (check-restored stopped)
        before (:forward/grant-id fixture)
        after (:effect/grant-id result)]
    (is (some? (:grant/revoked-at (fixtures/current-grant fixture before))))
    (is (uuid? after))
    (is (not= before after))
    (is (= :cancelled (:execution/status @(:host/context fixture))))
    (is (true? (get-in @(:host/context fixture) [:execution/input :opsv/safe-mode?])))
    (is (= [:proposed :committing :succeeded :proposed :committing :succeeded]
           (mapv :opsv/effect-state (forward/dispositions fixture))))))

(deftest ^{:stratum 1} missing-current-plan-approval-and-new-caller-attempt-ids-do-not-authorize-restoration-test
  (doseq [transform [fixtures/deny-plan!
                     #(assoc-in % [:request :effect/id] (random-uuid))]]
    (fixtures/with-input (partial check-refused transform))))

(deftest ^{:stratum 1} replay-retains-the-durable-recovery-disposition-without-new-authority-or-http-test
  (fixtures/with-input check-replay))

(deftest ^{:stratum 1} restoration-fences-forward-effects-without-changing-a-running-workflow-status-test
  (fixtures/with-input check-restored))

(deftest ^{:stratum 1} paused-blocked-and-failed-runs-retain-their-real-status-for-exact-compensation-test
  (doseq [status [:paused :blocked :failed]]
    (fixtures/with-input #(check-restored (fixtures/stop! status %)))))

(deftest ^{:stratum 1} transport-uncertainty-retains-the-transaction-and-revokes-recovery-authority-without-retry-test
  (fixtures/with-input check-uncertain))

(deftest ^{:stratum 1} failed-readiness-publication-prevents-restoration-and-revokes-the-exact-recovery-grant-test
  (fixtures/with-input check-publication-failure))

(deftest ^{:stratum 1} changed-policy-expiry-and-revocation-after-claim-prevent-recovery-mutation-test
  (doseq [change [:policy :expiry :revoke]]
    (fixtures/with-input (partial check-late-refusal change))))

(deftest ^{:stratum 1} recovery-requires-the-original-control-and-authority-directory-binding-test
  (fixtures/with-input check-foreign-runtime))

(deftest ^{:stratum 1} stable-compensation-identities-exclude-forward-operations-and-malformed-plan-hashes-test
  (fixtures/with-input check-identities))

(deftest ^{:stratum 1} recovery-slots-are-operation-specific-and-independent-of-caller-printer-bindings-test
  (fixtures/with-input check-printer-independent-identities))

(deftest ^{:stratum 1} admitted-forward-effects-must-settle-before-restoration-can-acquire-compensation-authority-test
  (fixtures/with-input check-forward-in-flight))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} cancelled-safe-mode-recovery-uses-new-authority-after-real-forward-grant-revocation-test
  (fixtures/with-forward-trial check-revoked-forward))

(comment
  (fixtures/with-input check-restored))
