;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.domain-fixtures)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} phase []
  {:phase/name :implement :phase/agent :test :phase/agent-instance-id (random-uuid)
   :phase/started-at #inst "2026-10-01" :phase/completed-at #inst "2026-10-01"
   :phase/duration-ms 0 :phase/output {} :phase/artifacts []
   :phase/inner-loop-iterations 0 :phase/event-stream-range {:start-seq 10 :end-seq 12}})
