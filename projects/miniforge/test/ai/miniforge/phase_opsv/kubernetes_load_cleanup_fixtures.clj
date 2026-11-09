;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-request-fixtures :as wire]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prepare
  "Actual current cleanup construction with only HTTP substituted."
  (partial wire/prepare phase/prepare-kubernetes-load-cleanup-proposal!))

(comment ::actual-retained-plan-admission-and-durable-native-creation-are-supplied-by-stop-fixtures)
