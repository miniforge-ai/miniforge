;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.lifecycle-artifacts-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.actuation-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.lifecycle-outcome :as outcome]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acknowledged-output [ids]
  {:opsv/phase-produced-artifact-refs ids
   :opsv/artifact-refs [(random-uuid)]})

(defn- ^{:stratum 0} failed-output [output]
  (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed)
                   {:opsv/phase-output output}))

(defn- ^{:stratum 0} assert-phase-receipts [ctx handler]
  (let [completed (fixture/step ctx handler)
        result (get-in completed [:phase :result])
        receipts (get-in result [:output :opsv/phase-produced-artifact-refs])]
    (is (vector? receipts))
    (is (= receipts (:artifacts result)))
    (is (nat-int? (get-in completed [:phase :duration-ms])))
    completed))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-lifecycle [ctx _directory]
  (let [completed (reduce assert-phase-receipts ctx support/handlers)
        results (:execution/phase-results completed)]
    (is (= [] (get-in results [:opsv/plan :result :artifacts])))
    (is (= 1 (count (get-in results [:opsv/actuate :result :artifacts]))))))

(defn- ^{:stratum 1} verify-checkpoint-refusal [ctx _directory]
  (let [transform (constantly (acknowledged-output [(random-uuid)]))
        interceptor (lifecycle/interceptor {} :opsv/actuate transform)
        failure (failed-output {})
        entered (with-redefs [snapshot/encode (constantly failure)]
                  ((:enter interceptor) (fixture/configured ctx)))
        result (get-in entered [:phase :result])]
    (is (= :error (:status result)))
    (is (not (contains? result :artifacts)))
    (is (not (contains? (get-in result [:output :anomaly/data :opsv/phase-output])
                        :opsv/phase-produced-artifact-refs)))))

(deftest ^{:stratum 1} result-preserves-explicit-empty-and-partial-receipts
  (doseq [ids [[] [(random-uuid)]]]
    (let [output (acknowledged-output ids)
          failure (failed-output output)]
      (is (= ids (:artifacts (outcome/phase-result output))))
      (is (= ids (:artifacts (outcome/phase-result failure))))
      (is (= :error (:status (outcome/phase-result failure)))))))

(deftest ^{:stratum 1} cumulative-or-missing-references-are-not-phase-artifacts
  (let [unconfirmed (dissoc (acknowledged-output []) :opsv/phase-produced-artifact-refs)]
    (doseq [output [unconfirmed (failed-output unconfirmed) {}]]
      (is (not (contains? (outcome/phase-result output) :artifacts))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} native-lifecycle-exposes-only-current-phase-receipts
  (fixture/with-context verify-lifecycle))

(deftest ^{:stratum 2} pre-publication-checkpoint-refusal-cannot-claim-inherited-receipts
  (fixture/with-context verify-checkpoint-refusal))
