;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance
  "Evaluate OPSV gates from trusted runtime policy, never caller verdicts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.governance-evaluation :as evaluation]
            [ai.miniforge.phase-opsv.governance-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} evaluate
  "Only :execution/opts may supply policy. Missing policy denies; invalid policy
   returns an anomaly. Evidence always comes from the verified pipeline output."
  [ctx verified]
  (let [policy (get-in ctx [:execution/opts :opsv/governance])
        pack (get-in ctx [:execution/input :opsv/experiment-pack])]
    (if (and (some? policy) (not (m/validate schema/RuntimePolicy policy)))
      (anomaly/anomaly :invalid-input (msg/ts :governance/invalid-policy) {})
      (evaluation/evaluate opsv/opsv-gate-ids ctx pack verified policy))))

(comment
  (evaluate {} {}))
