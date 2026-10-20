;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-publication
  "Terminal publication refusal is an authoritative machine transition.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} refusal-event :publication/fail)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} terminal-refusal [config]
  (if (= :final (:type config))
    (assoc-in config [:on refusal-event] :failed)
    config))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-refusal-transitions
  "Add refusal transitions to terminal states in a workflow topology.
   The workflow topology owns the required :failed destination."
  [states]
  (update-vals states terminal-refusal))

(comment ::durable-terminal-refusal)
