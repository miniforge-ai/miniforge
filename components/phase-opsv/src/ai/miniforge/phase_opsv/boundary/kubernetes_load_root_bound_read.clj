;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-root-bound-read
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-target :as target]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read! [settings roots operation]
  (let [until (io/deadline (:kubernetes/timeout-ms settings))]
    (anomaly/let-ok [_before (target/confirm! settings until roots)
                    observed (operation until)
                    _after (target/confirm! settings until roots)]
      observed)))

(comment ::fresh-physical-root-identity-surrounds-the-actual-owned-resource-read)
