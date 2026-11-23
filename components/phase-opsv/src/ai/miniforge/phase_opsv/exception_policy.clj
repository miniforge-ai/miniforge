;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.exception-policy
  "Exception selectors shared by OPSV adapter boundaries.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} nonfatal? [failure]
  (not (instance? Error failure)))

(comment
  (nonfatal? {:anomaly/type :unavailable}))
