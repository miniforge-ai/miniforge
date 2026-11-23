;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-preparation-test
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.verification-preparation-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} preparation-callback-is-distinct-from-verification-effects-test
  (let [calls (atom [])
        prepare (partial fixtures/record-preparation calls)
        verify (partial fixtures/record-verification calls)
        adapter (phase/functional-adapter identity identity prepare verify)
        request (fixtures/request)
        receipt (phase/prepare-verification adapter request)]
    (is (m/validate phase/VerificationPreparationReceipt receipt))
    (is (= request (select-keys receipt (keys request))))
    (is (= [[:prepare request]] @calls))
    (is (= request (phase/run-verification adapter request)))
    (is (= [[:prepare request] [:verify request]] @calls))))

(deftest ^{:stratum 0} missing-preparation-is-unavailable-not-a-fabricated-baseline-test
  (doseq [adapter [(phase/functional-adapter identity identity)
                   (phase/functional-adapter identity identity identity)]]
    (is (= :unavailable (:anomaly/type (phase/prepare-verification adapter (fixtures/request)))))))

(deftest ^{:stratum 0} preparation-receipt-refuses-stale-correlation-and-control-fields-test
  (let [request (fixtures/request)
        observations (fixtures/observations)]
    (doseq [[key value] [[:verification/id (random-uuid)]
                         [:candidate/hash "old"] [:experiment-pack/hash "old"]]]
      (is (= :conflict
             (:anomaly/type (phase/verification-preparation-receipt request (assoc observations key value))))))
    (doseq [invalid [nil [] {}
                     (assoc observations :environment-fingerprint {})
                     (assoc observations :runtime/target nil)
                     (assoc observations :verification/passed? true)
                     (assoc observations :execution-grant/allow? true)]]
      (is (= :invalid-input
             (:anomaly/type (phase/verification-preparation-receipt request invalid)))))))

(deftest ^{:stratum 0} preparation-callback-rejects-invokable-data-test
  (doseq [callback [nil {} [] #{} :callback]]
    (is (= :invalid-input
           (:anomaly/type (phase/functional-adapter identity identity callback identity))))))

(deftest ^{:stratum 0} preparation-without-population-is-typed-but-not-admitted-test
  (let [request (fixtures/request)
        observation (dissoc (fixtures/observations) :comparison/replica-observations)
        receipt (phase/verification-preparation-receipt request observation)]
    (is (m/validate phase/VerificationPreparationReceipt receipt))))

(deftest ^{:stratum 0} preparation-refuses-nonmap-request-before-correlation-test
  (doseq [request [nil [] :not-a-request]]
    (is (= :invalid-input
           (:anomaly/type (phase/verification-preparation-receipt request (fixtures/observations)))))))

(deftest ^{:stratum 0} preparation-callback-accepts-bound-function-vars-test
  (let [adapter (phase/functional-adapter #'identity #'identity #'identity #'identity)]
    (is (= :request (phase/prepare-verification adapter :request)))))

(comment ::freshness-and-target-match-are-host-admission-obligations)
