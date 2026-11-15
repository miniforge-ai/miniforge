;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as forward]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-observation :as observation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-admission :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup :as cleanup]
            [ai.miniforge.phase-opsv.kubernetes-load-stop :as stop]
            [ai.miniforge.phase-opsv.kubernetes-recovery-effect :as identity]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} requirements
  {:stop-load stop/ready?
   :cleanup-load cleanup/ready?})

(defn- ^{:stratum 0} runtime-bound? [operation runtime input ready]
  (and (= (:effects-directory runtime) (get-in input [:ownership/runtime :effects-directory]))
       (identity/matches? input ready operation)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} observe! [operation input ready]
  (let [plan (:recovery/plan ready)
        permitted? (get requirements operation)]
    (anomaly/let-ok [observed (observation/observe! (:context ready) input plan)]
      (if (permitted? input plan observed)
        (assoc ready :ownership/observation observed)
        (assoc-in (forward/refused) [:anomaly/data :load/observation] observed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} readiness! [operation runtime input]
  (anomaly/let-ok [ready (recovery/readiness! runtime input)]
    (if (runtime-bound? operation runtime input ready)
      (observe! operation input ready)
      (forward/refused))))

(comment ::no-caller-assertion-replaces-the-current-owned-uid-root-identity-or-quiescence)
