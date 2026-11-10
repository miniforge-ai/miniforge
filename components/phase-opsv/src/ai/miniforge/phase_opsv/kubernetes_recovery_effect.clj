;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-effect
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} identity-for [digest operation]
  (let [identity (pr-str [:opsv/recovery digest operation])]
    (UUID/nameUUIDFromBytes (.getBytes identity StandardCharsets/UTF_8))))

(comment ::one-durable-slot-per-operation-of-the-same-bounded-plan-not-per-caller-attempt)
