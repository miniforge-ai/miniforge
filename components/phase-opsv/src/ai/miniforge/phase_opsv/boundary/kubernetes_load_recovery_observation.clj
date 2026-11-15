;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-observation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-ownership :as ownership]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-quiescence :as quiescence]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-root-bound-read :as target-read]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-target :as target]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as original]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read-owned! [runtime plan _until]
  (quiescence/read! runtime plan))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [ctx input plan]
  (let [runtime (:ownership/runtime input)]
    (anomaly/let-ok [baseline (original/read! ctx (get-in input [:recovery/input :load :request])
                                            (:comparison/artifact-ref plan))
                    admitted (ownership/admit! runtime plan)
                    roots (target/original baseline (get-in plan [:recovery/binding :runtime/target]))]
      (target-read/read! (:settings admitted) roots (partial read-owned! runtime plan)))))

(comment ::the-reader-remains-bounded-and-the-enclosing-root-deadline-is-rechecked-after-it)
