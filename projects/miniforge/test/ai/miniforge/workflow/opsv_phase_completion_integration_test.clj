;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-phase-completion-integration-test
  (:require [ai.miniforge.phase-opsv.artifact-test-support :as material]
            [ai.miniforge.workflow.opsv-phase-completion-support :as support]
            [clojure.test :refer [deftest]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-phase-facts-reach-durable-workflow-completion-events
  (material/with-context support/with-journal))
