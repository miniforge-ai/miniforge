;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.candidate-verification-failures-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-failure [failure _request] (throw failure))

(defn- ^{:stratum 0} changed-receipt [callback key value request]
  (assoc (callback request) key value))

(defn- ^{:stratum 0} verify-unpublishable-observations [ctx directory baseline]
  (let [calls (atom [])
        realized (atom 0)
        deferred (lazy-seq (swap! realized inc) [1])]
    (doseq [raw [{:bad (Object.)} {:deferred deferred}]]
      (reset! calls [])
      (let [observed (assoc (fixtures/observations baseline) :environment/raw-observations raw)
            result (fixtures/verify ctx (partial fixtures/prepare calls observed)
                                       (partial fixtures/measure calls directory observed))]
        (is (anomaly/anomaly? result))
        (is (= [:prepare] (mapv first @calls)))))
    (is (zero? @realized))))

(defn- ^{:stratum 0} verify-final-publication-failure [ctx directory baseline]
  (let [publish artifact/publish!
        block? (atom false)
        calls (atom [])
        observed (fixtures/observations (merge baseline (environment/changes)))
        measure (fn [request]
                  (let [result (fixtures/measure calls directory observed request)]
                    (reset! block? true)
                    result))
        result (with-redefs [artifact/publish!
                            (fn [& args]
                              (if @block?
                                (anomaly/anomaly :unavailable "injected final publication failure" {})
                                (apply publish args)))]
                 (fixtures/verify ctx (partial fixtures/prepare calls (fixtures/observations baseline)) measure))]
    (is (= :unavailable (:anomaly/type result)))
    (is (= [:prepare :verify] (mapv first @calls)))
    (is (not (true? (get-in result [:opsv/verification-result :passed?]))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-stale-and-forged-receipts [ctx directory baseline]
  (let [calls (atom [])
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        candidate (merge baseline (environment/changes))
        measure (partial fixtures/measure calls directory (fixtures/observations candidate))]
    (doseq [[key value] [[:verification/id (random-uuid)]
                         [:candidate/hash "stale"] [:experiment-pack/hash "stale"]]]
      (reset! calls [])
      (is (= :conflict (:anomaly/type (fixtures/verify ctx (partial changed-receipt prepare key value) measure))))
      (is (= [:prepare] (mapv first @calls)))
      (is (= :conflict (:anomaly/type (fixtures/verify ctx prepare (partial changed-receipt measure key value))))))
    (doseq [key [:verification/output :comparison/pin :comparison/expected-hash :comparison/artifact-ref]]
      (is (= :invalid-input
              (:anomaly/type (fixtures/verify ctx prepare (partial changed-receipt measure key {}))))))))

(defn- ^{:stratum 1} verify-callback-failures [ctx directory baseline]
  (let [calls (atom [])
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        measure (partial fixtures/measure calls directory
                         (fixtures/observations (merge baseline (environment/changes))))]
    (doseq [stage [:preparation :candidate]]
      (let [run (if (= stage :preparation)
                  #(fixtures/verify ctx % measure)
                  #(fixtures/verify ctx prepare %))]
        (is (= :unavailable (:anomaly/type (run (partial throw-failure (java.io.IOException.))))))
        (is (thrown? AssertionError (run (partial throw-failure (AssertionError.)))))
        (let [interrupted (InterruptedException.)]
          (try
            (run (partial throw-failure interrupted))
            (is false "interruption must propagate")
            (catch InterruptedException caught
              (let [interrupted? (.isInterrupted (Thread/currentThread))]
                (is (identical? interrupted caught))
                (is interrupted?)))
            (finally (Thread/interrupted))))))))

(deftest ^{:stratum 1} nonportable-or-deferred-raw-data-cannot-reach-effects-test
  (fixtures/with-context verify-unpublishable-observations))

(deftest ^{:stratum 1} unconfirmed-final-observation-cannot-become-verification-success-test
  (fixtures/with-context verify-final-publication-failure))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} stale-correlations-and-adapter-forged-host-control-fields-are-rejected-test
  (fixtures/with-context verify-stale-and-forged-receipts))

(deftest ^{:stratum 2} both-adapter-boundaries-preserve-unavailable-fatal-and-cancellation-test
  (fixtures/with-context verify-callback-failures))
