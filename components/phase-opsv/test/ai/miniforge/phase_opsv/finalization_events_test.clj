;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-events-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.finalization :as finalization]
            [ai.miniforge.phase-opsv.finalization-events :as publication]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unavailable-event [& _]
  (throw (java.io.IOException. "event unavailable")))

(defn- ^{:stratum 0} verify-refusal [emit]
  (let [output {:opsv/phase-produced-artifact-refs [(random-uuid)]}
        finalized (atom [])
        failure (with-redefs [events/emit-phase-events! emit
                              finalization/finalize! (fn [& args] (swap! finalized conj args))]
                  (publication/finalize-after-events! {} output))]
    (is (anomaly/anomaly? failure))
    (is (= output (get-in failure [:anomaly/data :opsv/phase-output])))
    (is (empty? @finalized))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} event-refusals-and-exceptions-retain-post-artifact-output
  (doseq [emit [(constantly (anomaly/anomaly :unavailable "event refused" {})) unavailable-event]]
    (verify-refusal emit)))
