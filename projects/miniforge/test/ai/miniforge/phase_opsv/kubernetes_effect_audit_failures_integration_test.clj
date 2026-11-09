;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-audit-failures-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-record [transform {:keys [runtime recovery/record] :as fixture}]
  (let [changed (transform record)
        _stored (transactions/transition! (:effects-directory runtime) record changed)
        before (fixtures/assembly fixture)
        result (fixtures/audit fixture)]
    (is (anomaly/anomaly? result))
    (is (= before (fixtures/assembly fixture)))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-assembly [fixture]
  (doseq [transform [#(assoc % :opsv.assembly/status :finalized)
                     #(assoc % :evidence-bundle/workflow-id (random-uuid))
                     #(assoc % :evidence-bundle/id (random-uuid)) (constantly nil)]]
    (let [read-assembly evidence/get-opsv-assembly
          result (with-redefs [evidence/get-opsv-assembly (fn [& args] (transform (apply read-assembly args)))]
                   (fixtures/audit fixture))]
      (is (anomaly/anomaly? result))
      (is (empty? (fixtures/dispositions fixture))))))

(defn- ^{:stratum 0} check-artifact-failure [{:keys [recovery/record] :as fixture}]
  (let [failure (anomaly/anomaly :unavailable "injected artifact publication failure" {})
        before (fixtures/assembly fixture)
        result (with-redefs [artifact/publish! (constantly failure)] (fixtures/audit fixture))]
    (is (anomaly/anomaly? result))
    (is (= record (get-in result [:anomaly/data :effect/transaction])))
    (is (= before (fixtures/assembly fixture)))
    (is (empty? (fixtures/dispositions fixture)))))

(defn- ^{:stratum 0} check-event-acknowledgement [{:keys [recovery/record] :as fixture}]
  (let [accumulate evidence/accumulate-opsv-evidence!
        refuse-event (fn [store id material]
                       (when-not (contains? material :opsv/event-refs) (accumulate store id material)))
        result (with-redefs [evidence/accumulate-opsv-evidence! refuse-event] (fixtures/audit fixture))
        draft (get-in result [:anomaly/data :opsv/disposition-event])
        replay (fixtures/audit (assoc-in fixture [:ctx :opsv/disposition-events] [draft]))]
    (is (anomaly/anomaly? result))
    (is (= record (get-in result [:anomaly/data :effect/transaction])))
    (is (uuid? (:event/id draft)))
    (is (= record replay))
    (is (= 1 (count (fixtures/dispositions fixture))))
    (is (contains? (:opsv/event-refs (fixtures/assembly fixture)) (:event/id draft)))))

(defn- ^{:stratum 0} check-critical-reads [fixture]
  (with-redefs [effect/read-record (fn [& _] (throw (AssertionError. "fatal record read")))]
    (is (thrown? AssertionError (fixtures/audit fixture))))
  (let [interrupted (InterruptedException.)
        [caught flagged?] (wire/interrupted-result
                           #(with-redefs [effect/read-record (fn [& _] (throw interrupted))]
                              (fixtures/audit fixture)))]
    (is (identical? interrupted caught))
    (is flagged?)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} altered-record-authority-observations-or-workflow-do-not-become-audit-evidence-test
  (doseq [transform [#(assoc % :effect/state :succeeded)
                     #(assoc % :effect/state :unknown-outcome)
                     #(assoc % :effect/observed {:kubernetes/resource {:name "invented-before-commit"}})
                     #(assoc % :effect/class :effect/deploy)
                     #(assoc-in % [:effect/proposal :workflow-run/id] (random-uuid))]]
    (fixtures/with-proposed (partial check-record transform))))

(deftest ^{:stratum 1} finalized-foreign-or-missing-assemblies-refuse-before-publication-test
  (fixtures/with-proposed check-assembly))

(deftest ^{:stratum 1} missing-protected-artifact-publication-cannot-produce-a-disposition-test
  (fixtures/with-proposed check-artifact-failure))

(deftest ^{:stratum 1} unacknowledged-event-is-retained-and-replayed-without-duplicate-disposition-test
  (fixtures/with-proposed check-event-acknowledgement))

(deftest ^{:stratum 1} critical-record-read-failures-preserve-fatal-and-interruption-semantics-test
  (fixtures/with-proposed check-critical-reads))

(comment
  (fixtures/with-proposed check-event-acknowledgement))
