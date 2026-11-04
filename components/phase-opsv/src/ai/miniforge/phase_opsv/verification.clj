;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification
  "Orchestrate fresh candidate execution and verification result projection."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.verification-drift :as drift]
            [ai.miniforge.phase-opsv.verification-result :as result]
            [ai.miniforge.phase-opsv.verification-run :as run]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verified-output [ctx synthesized]
  (let [measured (run/execute ctx synthesized)
        safe-failure (if (anomaly/anomaly? measured) (reporting/protect measured) measured)
        recorded (drift/record! ctx safe-failure)]
    (reporting/protect (flow/continue recorded (partial result/output synthesized)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} output [ctx synthesized]
  (anomaly/let-ok [admitted (reporting/admit synthesized)]
    (verified-output ctx (:reporting/value admitted))))

(comment
  (output {} {}))
