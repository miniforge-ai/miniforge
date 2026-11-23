;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-execution
  "API surface class 1: exact owned-load compensation, not termination or absence proof."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution :as execution]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-cleanup :as cleanup]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-cleanup-provider :as cleanup-provider]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-operation :as kind]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-stop :as stop]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-stop-provider :as stop-provider]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} stop-operation
  (kind/definition :stop-load stop/prepare! stop-provider/patch!
    [:native-load-stop-readiness :opsv-verification-report :opsv/native-load-stop-readiness]))

(def ^{:stratum 0 :private true} cleanup-operation
  (kind/definition :cleanup-load cleanup/prepare! cleanup-provider/delete!
    [:native-load-cleanup-readiness :opsv-verification-report :opsv/native-load-cleanup-readiness]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stop! [runtime input]
  (execution/execute-forward! stop-operation runtime input))

(defn ^{:stratum 1} cleanup! [runtime input]
  (execution/execute-forward! cleanup-operation runtime input))

(comment
  (stop! {} {})
  (cleanup! {} {}))
