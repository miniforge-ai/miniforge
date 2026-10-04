;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.runtime-context
  "Resolve runtime telemetry consistently across OPSV phase boundaries.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stream [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn ^{:stratum 0} workflow-id [ctx]
  (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx)))

(defn ^{:stratum 0} adapter [ctx]
  (or (get-in ctx [:execution/opts :opsv/adapter])
      (get-in ctx [:execution/input :opsv/adapter])))

(comment
  (workflow-id {}))
