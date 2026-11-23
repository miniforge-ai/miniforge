;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualification-spec
  "Trusted host limits; no implicit enumeration or pin-acquisition budget.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PositiveLimit [:int {:min 1 :max Integer/MAX_VALUE}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Limits
  [:map {:closed true}
   [:pin/entry-limit PositiveLimit]
   [:pin/timeout-ms PositiveLimit]])
