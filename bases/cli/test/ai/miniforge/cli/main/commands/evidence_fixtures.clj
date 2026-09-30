;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence-fixtures
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bundle [overrides]
  (let [at #inst "2026-09-30T00:00:00Z"
        base {:evidence-bundle/id #uuid "00000000-0000-0000-0000-000000000001"
              :evidence-bundle/workflow-id #uuid "00000000-0000-0000-0000-000000000002"
              :evidence-bundle/created-at at
              :evidence-bundle/version "1.0.0"
              :evidence/intent {:intent/type :update
                                :intent/description "Test"
                                :intent/business-reason "Test"
                                :intent/constraints []
                                :intent/declared-at at}
              :evidence/policy-checks []
              :evidence/outcome {:outcome/success true}
              :compliance/created-at at
              :evidence/sealed-at at}
        value (merge base overrides)]
    (assoc value :evidence/content-hash (evidence/content-hash value))))

(comment
  (bundle {}))
