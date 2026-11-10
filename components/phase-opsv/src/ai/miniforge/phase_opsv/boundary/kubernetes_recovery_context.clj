;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-context
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as forward]
            [ai.miniforge.phase-opsv.kubernetes-recovery-execution-schema :as schema]
            [ai.miniforge.phase-opsv.run-control :as control]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-context? (m/validator schema/Context))

(defn- ^{:stratum 0} fenced [runtime ctx]
  (let [status (actuation/stop-mutations! (:fence runtime))]
    (if (zero? (:in-flight status)) ctx (forward/refused))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} current! [runtime]
  (anomaly/let-ok [ctx ((:current-context runtime))]
    (if (and (valid-context? ctx)
             (control/bound-to? (:control runtime) (:execution/id ctx)
                                (:authority-directory runtime) (:fence runtime)))
      (fenced runtime ctx)
      (forward/refused))))

(comment ::a-recovery-attempt-closes-forward-admission-without-changing-status-or-reopening-a-fence)
