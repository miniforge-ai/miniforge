;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.limits-spec
  "Finite positive signed-32-bit host bounds, without deployment defaults.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PositiveInteger [:int {:min 1 :max Integer/MAX_VALUE}])
