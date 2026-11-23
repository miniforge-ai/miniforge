;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.llm.cancellation
  "Whether the run that owns the calling thread has been cancelled. The
   workflow binds a check around each phase step; the streaming client
   reads it in its stream loop, so a cancel kills the agent's process tree
   within one poll, and a call started after the cancel ends at once.
   Nothing is interrupted, so the phase's own cleanup and boundary persist
   still run."
  (:require [ai.miniforge.llm.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :dynamic true} *cancel-requested?*
  "Zero-argument function returning true once the owning run is cancelled;
   nil outside a run that supplied one."
  nil)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-cancel-signal
  "Call F with CANCELLED? (a zero-argument function) as the cancel check
   for every LLM call F makes on this thread, and on futures it starts."
  [cancelled? f]
  (binding [*cancel-requested?* cancelled?]
    (f)))

(defn ^{:stratum 1} cancelled-timeout
  "The adaptive-timeout reason that ends a stream because its run was
   cancelled, ELAPSED-MS into it; nil while the run is not cancelled."
  [elapsed-ms]
  (when (and *cancel-requested?* (*cancel-requested?*))
    {:type       :cancelled
     :elapsed-ms elapsed-ms
     :message    (messages/t :stream.system/run-cancelled)}))
