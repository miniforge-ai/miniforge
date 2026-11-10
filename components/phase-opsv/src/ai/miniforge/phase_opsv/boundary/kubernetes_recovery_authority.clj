;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-authority
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-authority :as authority]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} issue! [runtime prepared now]
  ;; Recovery must not register in the already stopped forward grant bag.
  (authority/issue! (dissoc runtime :control) prepared now))

(comment ::exact-recovery-operation-and-actual-workflow-status-still-reach-the-ordinary-issuer)
