;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-recovery-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-schema :as restoration]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input (mu/dissoc restoration/Input :effect/id))

(def ^{:stratum 0} material [:baseline-recovery :opsv-verification-report :opsv/baseline-recovery])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple retained/Context Input])

(comment
  (count Input))
