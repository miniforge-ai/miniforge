;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected-decode [_]
  (throw (AssertionError. "Live assembly must not decode a checkpoint")))

(defn- ^{:stratum 0} assert-corruption-rejected [ctx _directory]
      (let [completed (f/step ctx (first support/handlers))
            detached (dissoc completed :opsv/evidence-assembly-store)
            corrupt (assoc-in detached [:execution/input :opsv/evidence-snapshot] "corrupt")
            wrong-run (assoc detached :execution/id (random-uuid))]
        (is (anomaly/anomaly? (runtime/ensure-assembly corrupt)))
        (is (anomaly/anomaly? (runtime/ensure-assembly wrong-run)))
        (is (not (anomaly/anomaly? (runtime/ensure-assembly detached))))))

(defn- ^{:stratum 0} assert-encoding-failure [ctx _directory]
      (let [failure (anomaly/anomaly :unavailable "snapshot unavailable" {})
            [phase-key transform] (first support/handlers)
            interceptor (lifecycle/interceptor {} phase-key transform)
            completed (with-redefs [artifact/encode-snapshot (constantly failure)]
                        ((:leave interceptor) ((:enter interceptor) ctx)))]
        (is (= :failed (get-in completed [:phase :status])))
        (is (= :error (get-in completed [:phase :result :status])))
        (is (map? (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output])))
        (is (= failure (get-in completed [:execution/input :opsv/evidence-snapshot])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-live-store-skips-decoding [ctx _directory]
  (let [completed (f/step ctx (first support/handlers))
        store (:opsv/evidence-assembly-store completed)
        entered (with-redefs [artifact/decode-snapshot unexpected-decode]
                  (runtime/ensure-assembly completed))]
    (is (identical? store (:opsv/evidence-assembly-store entered)))
    (is (= (get-in completed [:execution/input :opsv/evidence-assembly])
           (get-in entered [:execution/input :opsv/evidence-assembly])))))

(deftest ^{:stratum 1} checkpoint-corruption-cannot-fall-back-to-display-map-test
  (f/with-context assert-corruption-rejected))

(deftest ^{:stratum 1} failed-snapshot-cannot-report-phase-success-test
  (f/with-context assert-encoding-failure))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} live-assembly-does-not-decode-durable-snapshots-test
  (f/with-context assert-live-store-skips-decoding))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.evidence-checkpoint-test))
