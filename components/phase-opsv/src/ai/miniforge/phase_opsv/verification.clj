;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification
  "Orchestrate fresh candidate execution and verification result projection."
  (:require [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.verification-drift :as drift]
            [ai.miniforge.phase-opsv.verification-result :as result]
            [ai.miniforge.phase-opsv.verification-run :as run]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} output [ctx synthesized]
  (let [measured (run/execute ctx synthesized)
        recorded (drift/record! ctx measured)]
    (flow/continue recorded (partial result/output synthesized))))

(comment
  (output {} {}))
