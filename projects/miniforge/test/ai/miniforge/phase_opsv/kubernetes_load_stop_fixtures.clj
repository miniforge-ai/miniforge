;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-creation-fixtures :as creation]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-request-fixtures :as wire]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as retention]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prepare
  "Actual current stop construction with only HTTP substituted."
  (partial wire/prepare phase/prepare-kubernetes-load-stop-proposal!))

(defn- ^{:stratum 0} run-created! [f {:keys [input plan-id] :as fixture}]
  (let [{:keys [runtime] :as created} (creation/create! fixture)
        identity (random-uuid)
        request {:recovery/input input
                 :recovery/artifact-ref plan-id
                 :ownership/runtime runtime
                 :effect/id identity}]
    (f (merge fixture created {:request request}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-created [f]
  (retention/with-retained (partial run-created! f)))

(comment ::real-retained-admission-canonical-creation-root-grant-six-argument-commit-and-durable-storage)
