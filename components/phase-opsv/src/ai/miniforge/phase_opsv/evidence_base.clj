;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-base
  "One sanitized N6 base shared by preflight and durable actuation capture.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fields
  [:evidence-bundle/workflow-id :evidence-bundle/created-at
   :evidence-bundle/version :evidence/intent])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} bundle [ctx]
  (let [supplied (get-in ctx [:execution/opts :opsv/evidence-base])
        base (select-keys (when (map? supplied) supplied) fields)
        id (get-in ctx [:execution/input :opsv/evidence-bundle-id])]
    (assoc base :evidence-bundle/id id
                :evidence/policy-checks [] :evidence/outcome {:outcome/success true})))

(comment
  (bundle {}))
