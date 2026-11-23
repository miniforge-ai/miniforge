;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.interceptor
  "Bind effective deployment configuration at the phase entry boundary."
  (:require [ai.miniforge.phase.interface :as phase]
            ;; Register deployment defaults before constructing an interceptor.
            [ai.miniforge.phase-deployment.defaults]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} enter-configured [enter config ctx]
  (enter (assoc ctx :phase-config config)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configure [config ports]
  (let [merged (phase/merge-with-defaults config)
        enter (partial enter-configured (:enter ports) merged)]
    (assoc ports :name (:phase merged) :config merged :enter enter)))

(comment ::configured-deployment-entry)
