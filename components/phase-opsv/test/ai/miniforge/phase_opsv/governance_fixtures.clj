;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance-fixtures
  "Runtime policy fixtures for phase-level governance acceptance."
  (:require [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} policy
  {:policy/revision "opsv-governance-1.0.0"
   :policy/event-watermark 17
   :policy/context
   {:opsv/instrumentation-status {:cpu {:available? true :reliable? true}
                                  :backlog {:available? true :reliable? true}}
    :opsv/allowed-environments #{"staging"}
    :opsv/time-window-open-environments #{"staging"}
    :opsv/blast-radius-limits {:max-replica-delta 2 :max-node-delta 1
                              :allowed-namespaces #{"catalog"}}
    :opsv/apply-enabled? true
    :opsv/apply-service-allowlist #{"catalog"}}})

(def ^{:stratum 0} verified
  {:opsv/verification-result {:passed? true}
   :opsv/experiment-pack-hash "sha256:pack"
   :opsv/environment-fingerprint {:cluster "staging"}
   :opsv/metric-snapshot-artifact-refs [(random-uuid)]})

(def ^{:stratum 0} output-path [:execution/phase-results :opsv/verify :result :output])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} context []
  (-> (support/execution-context nil)
      (assoc-in [:execution/opts :opsv/governance] policy)
      (assoc-in output-path verified)))

(comment
  (context))
