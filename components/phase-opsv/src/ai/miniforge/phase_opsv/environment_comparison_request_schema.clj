;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-request-schema
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-comparison-schema :as contract]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Request
  [:map {:closed true}
   [:comparison/pin contract/Pin]
   [:comparison/expected-hash opsv/PolicyHash]
   [:comparison/binding contract/Binding]
   [:comparison/stage [:enum :candidate :rollback]]
   [:comparison/observed fingerprint/Fingerprint]])

(comment ::independently-pinned-comparison-request)
