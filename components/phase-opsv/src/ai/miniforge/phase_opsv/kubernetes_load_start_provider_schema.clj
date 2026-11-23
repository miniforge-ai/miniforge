;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-provider-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-json-patch :as patch]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-provider-schema :as owned]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Proposal (owned/proposal :start-load :patch patch/media-type [:vector :map]))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments (owned/arguments Proposal))

(comment
  (count Arguments))
