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

(deftest ^{:stratum 0} checkpoint-corruption-cannot-fall-back-to-display-map-test
  (f/with-context
    (fn [ctx _]
      (let [completed (f/step ctx (first support/handlers))
            detached (dissoc completed :opsv/evidence-assembly-store)
            corrupt (assoc-in detached [:execution/input :opsv/evidence-snapshot] "corrupt")
            wrong-run (assoc detached :execution/id (random-uuid))]
        (is (anomaly/anomaly? (runtime/ensure-assembly corrupt)))
        (is (anomaly/anomaly? (runtime/ensure-assembly wrong-run)))
        (is (not (anomaly/anomaly? (runtime/ensure-assembly detached))))))))

(deftest ^{:stratum 0} failed-snapshot-cannot-report-phase-success-test
  (f/with-context
    (fn [ctx _]
      (let [failure (anomaly/anomaly :unavailable "snapshot unavailable" {})
            [phase-key transform] (first support/handlers)
            interceptor (lifecycle/interceptor {} phase-key transform)
            completed (with-redefs [artifact/encode-snapshot (constantly failure)]
                        ((:leave interceptor) ((:enter interceptor) ctx)))]
        (is (= :failed (get-in completed [:phase :status])))
        (is (= :error (get-in completed [:phase :result :status])))
        (is (map? (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output])))
        (is (= failure (get-in completed [:execution/input :opsv/evidence-snapshot])))))))
