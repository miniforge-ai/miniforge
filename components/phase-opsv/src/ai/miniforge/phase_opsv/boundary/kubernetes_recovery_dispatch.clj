;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-dispatch
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-effect-authorization :as authority]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invoke! [runtime issued ready record operation]
  (authority/call! context/current! runtime issued ready record operation))

(comment ::current-exact-recovery-authority-does-not-enter-or-reset-the-forward-fence)
