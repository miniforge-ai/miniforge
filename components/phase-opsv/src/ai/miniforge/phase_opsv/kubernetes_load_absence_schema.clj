;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-absence-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as stop]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Input (mu/dissoc stop/Input :effect/id))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple retained/Context Input])

(comment
  (count Input))
