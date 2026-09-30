;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-artifacts
  "Link event projections to acknowledged phase artifacts, never caller hints.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} measurement-events
  #{:opsv/load-step :opsv.convergence/iteration :opsv.verification/result})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} link [output event]
  (let [ids (get output :opsv/phase-artifact-ids {})
        metric-refs (vec (keep ids [:metric-snapshot :verification-measurements]))
        policy-id (:policy ids)
        event-type (:event/type event)]
    (cond-> event
      (and (contains? measurement-events event-type) (seq metric-refs))
      (assoc :opsv/metric-snapshot-artifact-refs metric-refs)
      (and (= :opsv.policy/proposed event-type) policy-id)
      (assoc :opsv/policy-artifact-ref policy-id))))

(comment
  (link {} {:event/type :opsv/load-step}))
