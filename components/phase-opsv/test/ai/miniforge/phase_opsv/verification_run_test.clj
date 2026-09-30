;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-run-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} synthesized-context []
  (let [adapter (support/test-adapter support/ramp-steps)
        ctx (support/execution-context adapter)]
    (reduce support/run-transformation ctx (take 5 support/handlers))))

(defn- ^{:stratum 0} measured [calls measurements request]
  (swap! calls conj request)
  (phase/verification-receipt request measurements))

(defn- ^{:stratum 0} verify-with [ctx callback]
  (let [adapter (phase/functional-adapter identity identity callback)]
    (phase/verify (assoc-in ctx [:execution/opts :opsv/adapter] adapter))))

(defn- ^{:stratum 0} changed-receipt [key value request]
  (assoc (phase/verification-receipt request support/verification-measurements) key value))

(defn- ^{:stratum 0} failed-adapter [failure _request]
  (throw failure))

(deftest ^{:stratum 0} public-adapter-construction-validates-inputs-test
  (doseq [callback [nil {} [] #{} :callback]]
    (is (anomaly/anomaly? (phase/functional-adapter callback identity identity))))
  (let [adapter (phase/functional-adapter #'identity #'identity #'identity)]
    (is (= :request (phase/run-verification adapter :request))))
  (doseq [measurements [nil [] :not-measurements]]
    (is (anomaly/anomaly? (phase/verification-receipt {} measurements)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} fresh-candidate-observations-can-fail-successful-baseline-test
  (let [ctx (synthesized-context)
        synthesized (support/phase-output ctx :opsv/synthesize)
        calls (atom [])
        measurements (assoc support/verification-measurements
                            :observations {"cpu-utilization" 0.99 "backlog-per-replica" 800}
                            :confidence 0.99
                            :metric-snapshot-artifact-refs [(random-uuid)])
        callback (partial measured calls measurements)
        first-run (verify-with ctx callback)
        second-run (verify-with ctx callback)
        result (:opsv/verification-result first-run)]
    (is (true? (get-in synthesized [:opsv/convergence-result :evaluation :success-criteria-satisfied?])))
    (is (false? (:passed? result)))
    (is (= :high (:confidence result)))
    (is (= 2 (count @calls)))
    (is (= (:opsv/operational-policy synthesized) (:candidate/policy (first @calls))))
    (is (= (:opsv/experiment-pack synthesized) (:experiment-pack (first @calls))))
    (is (= (hash/content-hash (:opsv/operational-policy synthesized))
           (get-in first-run [:opsv/verification-run :candidate/hash])))
    (is (not= (get-in first-run [:opsv/verification-run :verification/id])
              (get-in second-run [:opsv/verification-run :verification/id])))
    (is (= (:metric-snapshot-artifact-refs measurements)
           (:opsv/metric-snapshot-artifact-refs first-run)))))

(deftest ^{:stratum 1} missing-malformed-and-replayed-measurements-fail-closed-test
  (let [ctx (synthesized-context)
        legacy (phase/functional-adapter identity identity)]
    (is (anomaly/anomaly? (phase/verify (assoc-in ctx [:execution/opts :opsv/adapter] legacy))))
    (doseq [[key value] [[:verification/id (random-uuid)] [:candidate/hash "old"]
                         [:experiment-pack/hash "old"] [:environment-fingerprint {}]
                         [:observations nil] [:confidence Double/NaN]
                         [:confidence Double/POSITIVE_INFINITY]
                         [:metric-snapshot-artifact-refs []]
                         [:metric-snapshot-artifact-refs ["not-an-artifact"]]]]
      (is (anomaly/anomaly? (verify-with ctx (partial changed-receipt key value)))))))

(deftest ^{:stratum 1} verification-preserves-legacy-adapter-and-runtime-precedence-test
  (let [legacy-calls (atom [])
        runtime-calls (atom [])
        legacy (phase/functional-adapter identity identity
                 (partial measured legacy-calls support/verification-measurements))
        runtime (phase/functional-adapter identity identity
                  (partial measured runtime-calls support/verification-measurements))
        ctx (-> (synthesized-context)
                (update :execution/opts dissoc :opsv/adapter)
                (assoc-in [:execution/input :opsv/adapter] legacy))]
    (is (not (anomaly/anomaly? (phase/verify ctx))))
    (is (not (anomaly/anomaly? (phase/verify (assoc-in ctx [:execution/opts :opsv/adapter] runtime)))))
    (is (anomaly/anomaly? (phase/verify (assoc-in ctx [:execution/opts :opsv/adapter] :invalid))))
    (is (= 1 (count @legacy-calls) (count @runtime-calls)))))

(deftest ^{:stratum 1} missing-nil-valued-observation-cannot-pass-verification-test
  (let [ctx (synthesized-context)
        calls (atom [])]
    (doseq [declared [{:foo nil} {:criteria [{:criterion/id "foo" :criterion/expected nil}]}]]
      (let [configured (assoc-in ctx [:execution/phase-results :opsv/synthesize :result :output
                                     :opsv/experiment-pack :experiment-pack/success-criteria] declared)
            missing (assoc support/verification-measurements :observations {"unrelated" nil})
            observed (assoc missing :observations {"foo" nil})]
        (is (anomaly/anomaly? (verify-with configured (partial measured calls missing))))
        (is (true? (get-in (verify-with configured (partial measured calls observed))
                          [:opsv/verification-result :passed?])))))))

(deftest ^{:stratum 1} receipt-constructor-refuses-stale-correlation-test
  (let [ctx (synthesized-context)
        calls (atom [])
        measured-run (verify-with ctx (partial measured calls support/verification-measurements))
        request (first @calls)
        receipt (:opsv/verification-run measured-run)]
    (is (= receipt (phase/verification-receipt request receipt)))
    (doseq [[key value] [[:verification/id (random-uuid)]
                         [:candidate/hash "old"] [:experiment-pack/hash "old"]]]
      (is (anomaly/anomaly? (phase/verification-receipt request (assoc receipt key value)))))
    (is (anomaly/anomaly? (verify-with ctx (partial measured calls receipt))))))

(deftest ^{:stratum 1} adapter-anomalies-and-fatal-outcomes-preserve-their-meaning-test
  (let [ctx (synthesized-context)
        unavailable (anomaly/anomaly :unavailable "test adapter unavailable" {})
        fatal (AssertionError. "fatal adapter failure")]
    (is (= unavailable (verify-with ctx (constantly unavailable))))
    (is (= :unavailable (:anomaly/type (verify-with ctx (partial failed-adapter (java.io.IOException.))))))
    (is (thrown? AssertionError (verify-with ctx (partial failed-adapter fatal))))))

(deftest ^{:stratum 1} interrupted-verification-preserves-cancellation-test
  (let [ctx (synthesized-context)
        interrupted (InterruptedException. "verification interrupted")]
    (try
      (verify-with ctx (partial failed-adapter interrupted))
      (is false "interruption must propagate")
      (catch InterruptedException caught
        (let [interrupted? (.isInterrupted (Thread/currentThread))]
          (is (identical? interrupted caught))
          (is interrupted?)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} invalid-candidate-context-is-refused-before-adapter-invocation-test
  (let [ctx (synthesized-context)
        calls (atom [])
        callback (partial measured calls support/verification-measurements)]
    (doseq [missing [:opsv/operational-policy :opsv/experiment-pack :opsv/environment-fingerprint]]
      (let [invalid (update-in ctx [:execution/phase-results :opsv/synthesize :result :output] dissoc missing)]
        (is (anomaly/anomaly? (verify-with invalid callback)))))
    (is (empty? @calls))))

(comment
  (synthesized-context))
