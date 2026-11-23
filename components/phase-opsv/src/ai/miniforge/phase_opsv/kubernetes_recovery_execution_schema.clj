;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-execution-schema
  (:require [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Context
  [:and retained/Context
   [:map [:execution/status [:enum :running :paused :blocked :failed :cancelled]]]])

(comment ::recovery-preserves-stopped-status-and-the-original-forward-control-identity)
