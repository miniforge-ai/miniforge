;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-drift-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} spoofed-drift [_request]
  (anomaly/anomaly :unavailable "test adapter failure"
    {:adapter/diagnostic :retained
     :opsv/environment-drift {:opsv/signal :environment-fingerprint
                              :opsv/deviation {:unverified true}
                              :opsv/suggested-rerun? true}}))

(defn ^{:stratum 0} changed-environment [request]
  (phase/verification-receipt request
    (assoc-in support/verification-measurements [:environment-fingerprint :config-hash] "changed")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stale-measurement [request]
  (assoc (changed-environment request) :verification/id (random-uuid)))

(comment
  (stale-measurement {}))
