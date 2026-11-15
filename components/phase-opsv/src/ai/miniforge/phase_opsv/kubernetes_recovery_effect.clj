;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-effect
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} identity-prefix
  "Domain-separate closed recovery hashes and operation names without caller printer state."
  "opsv/recovery/")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} identity-for [digest operation]
  (let [identity (str identity-prefix digest "/" (name operation))]
    (UUID/nameUUIDFromBytes (.getBytes identity StandardCharsets/UTF_8))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} matches? [input reference operation]
  (= (:effect/id input) (identity-for (:recovery/hash reference) operation)))

(comment ::one-durable-slot-per-operation-of-the-same-bounded-plan-not-per-caller-attempt)
