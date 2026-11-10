;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-execution-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-effect-publication-fault-fixtures :as publication]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence-fixtures :as quiescence]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-fault-fixtures :as faults]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as trial]
            [ai.miniforge.phase-opsv.prometheus-test-support :as http]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-success [operation {:keys [host/runtime record] :as fixture}]
  (let [directory (get-in fixture [:runtime :effects-directory])
        before (:effect/grant-id record)
        revoked (grant/revoke-stored! directory before :revocation/operator clock/now)
        stopped (->> fixture (recovery/stop! :cancelled) recovery/safe-mode!)
        [result calls] (fixtures/execute operation stopped)]
    (is (not (anomaly/anomaly? revoked)))
    (is (not (anomaly/anomaly? result)))
    (is (= :succeeded (:effect/state result)))
    (is (= :granted (:effect/authority result)))
    (fixtures/check-exact-admission operation fixture result)
    (is (= operation (get-in result [:effect/proposal :opsv/experiment :opsv/operation])))
    (is (= :effect/load-test (:effect/class result)))
    (is (not= before (:effect/grant-id result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id result))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= [:proposed :committing :succeeded] (mapv :opsv/effect-state (forward/dispositions fixture))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (not-any? #(contains? (:effect/observed result) %) [:load/terminated? :load/absent? :recovery/verified?]))))

(defn- ^{:stratum 0} check-refused [operation transform {:keys [host/runtime] :as fixture}]
  (let [[result calls] (fixtures/execute operation (transform fixture))]
    (is (anomaly/anomaly? result))
    (is (empty? (fixtures/mutations calls)))
    (is (empty? (forward/dispositions fixture)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} unproven-pod [fixture]
  (fixtures/with-pod fixture (update (:pod fixture) "spec" dissoc "schedulingGates")))

(defn- ^{:stratum 0} foreign-pod [fixture]
  (fixtures/with-pod fixture (assoc-in (:pod fixture) ["metadata" "uid"] "foreign-owned-name")))

(defn- ^{:stratum 0} foreign-directory [fixture]
  (update-in fixture [:request :ownership/runtime :effects-directory] str "-foreign"))

(defn- ^{:stratum 0} foreign-host-directory [fixture]
  (update-in fixture [:host/runtime :effects-directory] str "-foreign"))

(defn- ^{:stratum 0} new-attempt [fixture]
  (assoc-in fixture [:request :effect/id] (random-uuid)))

(defn- ^{:stratum 0} check-replay [operation fixture]
  (let [[initial _calls] (fixtures/execute operation fixture)
        [result calls] (fixtures/execute operation fixture)]
    (is (= :succeeded (:effect/state initial)))
    (is (= :conflict (:anomaly/type result)))
    (is (= initial (get-in result [:anomaly/data :effect/transaction])))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-uncertain [operation {:keys [host/runtime] :as fixture}]
  (let [pending (http/failed-future (java.io.IOException.))
        [result calls] (fixtures/execute-response operation fixture pending (constantly nil))
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (= :unknown-outcome (:effect/state record)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))
    (is (some? (:grant/revoked-at (recovery/current-grant fixture (:effect/grant-id record)))))))

(defn- ^{:stratum 0} check-publication-failure [operation fixture]
  (let [material (fixtures/readiness-kind operation)
        execute! (partial fixtures/execute operation)
        {:keys [result calls artifact]} (publication/execute execute! material fixture)
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (anomaly/anomaly? result))
    (is (= :failed (:effect/state record)))
    (is (some? (:artifact/content artifact)))
    (is (empty? (fixtures/mutations calls)))
    (is (some? (:grant/revoked-at (recovery/current-grant fixture (:effect/grant-id record)))))))

(defn- ^{:stratum 0} check-late-refusal [operation change {:keys [host/runtime] :as fixture}]
  (let [armed (atom true)
        change! (partial trial/change! change)
        [result calls] (fixtures/execute operation fixture (partial forward/on-claim! change! armed))]
    (is (false? @armed))
    (is (anomaly/anomaly? result))
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (empty? (fixtures/mutations calls)))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-late-drift [operation resource key fixture]
  (let [path (case resource :pod (:load/path fixture) :namespace inventory/namespace-path)
        expected (case resource :pod :unknown-outcome :namespace :failed)
        changed [path "metadata" key]
        [result calls] (faults/execute-with-drift operation changed "changed-after-claim" fixture)
        record (get-in result [:anomaly/data :effect/transaction])]
    (is (anomaly/anomaly? result))
    (is (= expected (:effect/state record)))
    (is (empty? (fixtures/mutations calls)))
    (is (some? (:grant/revoked-at (recovery/current-grant fixture (:effect/grant-id record)))))))

(defn- ^{:stratum 0} check-effect-denied [operation late? {:keys [host/runtime] :as fixture}]
  (let [armed (atom true)
        changed (if late? fixture (recovery/deny-effect! fixture))
        on-read! (if late? (partial forward/on-claim! recovery/deny-effect! armed) (constantly nil))
        [result calls requests] (fixtures/execute operation changed on-read!)]
    (is (anomaly/anomaly? result))
    (is (empty? (fixtures/mutations calls)))
    (is (= (if late? 1 0) (count requests)))
    (is (= (if late? :failed nil) (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (true? (:stopped? (actuation/mutation-status (:fence runtime)))))))

(defn- ^{:stratum 0} check-pregrant-drift [operation fixture]
  (let [[result calls requests] (faults/execute-with-pregrant-drift operation fixture)]
    (is (anomaly/anomaly? result))
    (is (empty? (fixtures/mutations calls)))
    (is (empty? requests))
    (is (empty? (forward/dispositions fixture)))))

(defn- ^{:stratum 0} check-running [operation fixture]
  (let [[result calls requests] (fixtures/execute operation fixture)]
    (is (= :succeeded (:effect/state result)))
    (is (= :running (:workflow-run/status (first requests))))
    (is (= 1 (count (fixtures/mutations calls))))
    (fixtures/check-exact-admission operation fixture result)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} independently-authorized-stop-remains-available-after-forward-creation-authority-is-revoked-test
  (fixtures/with-input :stop-load (partial check-success :stop-load)))

(deftest ^{:stratum 1} independently-authorized-inert-load-cleanup-is-not-an-absence-or-recovery-claim-test
  (fixtures/with-input :cleanup-load (partial check-success :cleanup-load)))

(deftest ^{:stratum 1} normal-running-workflows-use-separate-exact-load-recovery-authority-without-fabricating-status-test
  (doseq [operation [:stop-load :cleanup-load]]
    (fixtures/with-input operation (partial check-running operation))))

(defn- ^{:stratum 1} check-terminated [fixture]
  (let [observed (quiescence/terminated-pod (:pod fixture))]
    (check-success :cleanup-load (fixtures/with-pod fixture observed))))

(deftest ^{:stratum 1} unproven-load-quiescence-refuses-cleanup-before-any-authority-is-issued-test
  (fixtures/with-input :cleanup-load (partial check-refused :cleanup-load unproven-pod)))

(deftest ^{:stratum 1} exact-original-uid-plan-approval-runtime-directory-and-fixed-operation-slot-are-required-test
  (doseq [operation [:stop-load :cleanup-load]
          transform [foreign-pod recovery/deny-plan! foreign-directory foreign-host-directory new-attempt]]
    (fixtures/with-input operation (partial check-refused operation transform))))

(deftest ^{:stratum 1} replay-does-not-acquire-new-authority-or-call-the-provider-test
  (doseq [operation [:stop-load :cleanup-load]]
    (fixtures/with-input operation (partial check-replay operation))))

(deftest ^{:stratum 1} uncertain-compensation-is-retained-and-authority-revoked-without-a-retry-test
  (doseq [operation [:stop-load :cleanup-load]]
    (fixtures/with-input operation (partial check-uncertain operation))))

(deftest ^{:stratum 1} failed-readiness-publication-prevents-both-compensation-mutations-test
  (doseq [operation [:stop-load :cleanup-load]]
    (fixtures/with-input operation (partial check-publication-failure operation))))

(deftest ^{:stratum 1} fresh-policy-revocation-and-expiry-after-durable-claim-prevent-compensation-test
  (doseq [operation [:stop-load :cleanup-load]
          change [:policy :revoke :expiry]]
    (fixtures/with-input operation (partial check-late-refusal operation change))))

(deftest ^{:stratum 1} physical-root-owned-uid-and-exact-current-revision-remain-sensitive-after-claim-test
  (doseq [operation [:stop-load :cleanup-load]
          [resource key] [[:namespace "uid"] [:pod "uid"] [:pod "resourceVersion"]]]
    (fixtures/with-input operation (partial check-late-drift operation resource key))))

(deftest ^{:stratum 1} plan-approval-is-not-exact-load-effect-admission-before-issuance-or-after-claim-test
  (doseq [operation [:stop-load :cleanup-load] late? [false true]]
    (fixtures/with-input operation (partial check-effect-denied operation late?))))

(deftest ^{:stratum 1} fresh-native-preconditions-must-still-match-construction-before-any-root-grant-is-issued-test
  (doseq [operation [:stop-load :cleanup-load]]
    (fixtures/with-input operation (partial check-pregrant-drift operation))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} independently-authorized-terminated-load-cleanup-requires-actual-container-observation-test
  (fixtures/with-input :cleanup-load check-terminated))

(comment
  (fixtures/with-input :stop-load (partial check-success :stop-load)))
