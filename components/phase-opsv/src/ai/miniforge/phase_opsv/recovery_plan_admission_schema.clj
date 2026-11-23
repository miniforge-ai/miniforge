;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-admission-schema
  (:require [ai.miniforge.kubernetes-target.interface :as target]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as preparation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Settings
  "Independent runtime approval; absence denies admission, never enables experiments."
  [:map {:closed true}
   [:environment/class [:= :staging]]
   [:recovery/environment target/IdentityText]
   [:recovery/declaration preparation/Declaration]
   [:recovery/approved-plan-hashes [:set {:min 1} opsv/PolicyHash]]])

(comment ::runtime-owned-exact-plan-approval-not-forward-or-recovery-execution-authority)
