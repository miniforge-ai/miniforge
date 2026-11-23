;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-target-schema
  (:require [ai.miniforge.kubernetes-target.interface :as target]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Target
  target/Target)

(comment ::one-runtime-target-contract)
