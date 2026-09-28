;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.actuation-decision
  "Build one shared monotonic authority decision for recommendation and execution.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} input [ctx verified]
  {:requested-actuation-mode (get-in ctx [:execution/input :opsv/experiment-pack
                                          :experiment-pack/actuation-intent])
   :verification-passed? (get-in verified [:opsv/verification-result :passed?])
   :gate-results (:opsv/gate-results verified)
   :safe-mode? (true? (get-in ctx [:execution/input :opsv/safe-mode?]))
   :pr-capability-valid? false
   :apply-capability-valid? false
   :rollback-verified? false
   :postconditions-configured? false})

(comment
  (input {} {}))
