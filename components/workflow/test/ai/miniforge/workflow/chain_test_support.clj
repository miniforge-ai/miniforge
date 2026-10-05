;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} definition [id version]
  {:chain/id id
   :chain/version version
   :chain/steps [{:step/id :spec :step/workflow-id :spec-step}]})

(comment
  (definition :example "1.0.0"))
