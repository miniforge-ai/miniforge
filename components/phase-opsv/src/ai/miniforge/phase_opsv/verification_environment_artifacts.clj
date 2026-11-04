;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment-artifacts
  (:require [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.artifacts :as artifacts]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ready [ctx]
  (if (get-in ctx [:execution/opts :opsv/artifact-directory])
    (artifacts/prepare ctx)
    (confirmation/failure {})))

(defn ^{:stratum 0} retain! [ctx output content]
  (let [value (assoc output :opsv/verification-environment content)]
    (confirmation/publish-with-exception-handling ctx
      (model/record ctx value model/verification-environment-material) value)))

(comment ::acknowledged-immutable-evidence-before-candidate-effects)
