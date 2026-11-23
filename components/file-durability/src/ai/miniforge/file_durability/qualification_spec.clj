;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-spec
  "Trusted host pin and handshake bounds; no implicit acquisition or cleanup budget."
  (:require [ai.miniforge.file-durability.limits-spec :as bounds]
            [ai.miniforge.file-durability.lock-spec :as lock]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PinLimits
  [:map {:closed true}
   [:pin/entry-limit bounds/PositiveInteger]
   [:pin/timeout-ms lock/Milliseconds]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Limits
  [:map {:closed true}
   [:qualification/pin-limits PinLimits]
   [:qualification/lock-limits lock/Limits]])
