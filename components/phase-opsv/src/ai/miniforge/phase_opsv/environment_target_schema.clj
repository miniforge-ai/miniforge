;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-target-schema
  (:require [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Target
  [:map {:closed true}
   [:cluster fingerprint/Text]
   [:namespace fingerprint/Text]
   [:namespace-uid fingerprint/Text]
   [:workload-uid fingerprint/Text]])

(comment ::one-runtime-target-contract)
