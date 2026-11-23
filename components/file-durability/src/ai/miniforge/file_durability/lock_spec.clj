;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-spec
  "Trusted deployment bounds for the isolated cross-runtime handshake."
  (:require [ai.miniforge.file-durability.limits-spec :as bounds]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Milliseconds bounds/PositiveInteger)

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Limits
  [:map {:closed true}
   [:lock/timeout-ms Milliseconds]
   [:lock/poll-interval-ms Milliseconds]
   [:lock/exit-timeout-ms Milliseconds]])

(comment (m/validate Limits {}))
