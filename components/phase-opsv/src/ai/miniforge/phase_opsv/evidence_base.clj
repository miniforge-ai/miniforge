;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-base
  "One sanitized N6 base shared by preflight and durable actuation capture."
  (:require [ai.miniforge.phase-opsv.evidence-linkage :as linkage]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fields
  [:evidence-bundle/workflow-id :evidence-bundle/created-at
   :evidence-bundle/version :evidence/intent])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} bundle [ctx]
  (let [supplied (get-in ctx [:execution/opts :opsv/evidence-base])
        base (select-keys (when (map? supplied) supplied) fields)
        id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        tier (get-in ctx [:opsv/recovered-evidence-base :evidence/outcome :outcome/tier]
                     (get (:execution/workflow ctx) :workflow/tier :standard))
        links (linkage/links ctx)]
    (cond-> (assoc base :evidence-bundle/id id :evidence/policy-checks []
                       :evidence/outcome {:outcome/success true :outcome/tier tier})
      links (assoc :evidence/event-links links))))

(comment
  (bundle {}))
