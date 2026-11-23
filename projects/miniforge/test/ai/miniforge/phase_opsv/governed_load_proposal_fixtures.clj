;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-load-proposal-fixtures
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-issuance-fixtures :as issuance]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as native]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} usage [scope] {:effect/scope scope :usage/count 1})

(defn ^{:stratum 0} runtime []
  {:load/image (str "docker.io/fortio/fortio@sha256:" (apply str (repeat 64 "a")))
   :load/cpu-millis 100 :load/memory-mib 64})

(defn ^{:stratum 0} argument [proposal flag]
  (let [args (get-in proposal [:kubernetes/request :body "spec" "containers" 0 "args"])]
    (second (drop-while #(not= flag %) args))))

(defn ^{:stratum 0} issuance-request [proposal]
  (let [keys (get-in grant/issuance-policies [:opsv/experiment :policy/scope-keys])]
    (assoc (select-keys proposal keys) :workflow-run/status :running
           :effect/class (:effect/class proposal) :effect/preflight (issuance/preflight))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} input [original]
  (assoc (select-keys original [:declaration :request :comparison/artifact-ref :limits]) :runtime (runtime)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input [f]
  (native/with-input #(f (update % :input input))))

(comment ::real-native-original-and-protected-publication-never-download-the-fixture-image)
