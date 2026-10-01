;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence-fixtures
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bundle [overrides]
  (let [at #inst "2026-09-30T00:00:00Z"
        workflow-id #uuid "00000000-0000-0000-0000-000000000002"
        base {:evidence-bundle/id #uuid "00000000-0000-0000-0000-000000000001"
              :evidence-bundle/workflow-id workflow-id
              :evidence-bundle/created-at at
              :evidence-bundle/version "1.0.0"
              :evidence/intent {:intent/type :update
                                :intent/description "Test"
                                :intent/business-reason "Test"
                                :intent/constraints []
                                :intent/declared-at at}
              :evidence/policy-checks []
              :evidence/event-links [{:event-links/scope-type :workflow :event-links/scope-id workflow-id
                                      :event-links/from-sequence 0 :event-links/to-sequence 1
                                      :event-links/event-count 2}]
              :evidence/outcome {:outcome/success true :outcome/tier :standard}
              :compliance/created-at at
              :compliance/sensitive-data false :compliance/pii-handling :none
              :evidence/sealed-at at}
        value (merge base overrides)]
    (assoc value :evidence/content-hash (evidence/content-hash value))))

(comment
  (bundle {}))
