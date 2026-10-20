;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-terminal-integration-test
  (:require [ai.miniforge.workflow.opsv-terminal-support :as support]
            [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [clojure.test :refer [deftest use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} real-workflow-checkpoint-recovers-terminal-evidence-without-provider-test
  (checkpoint/call-with-temp-checkpoint-root support/run-and-recover!))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-terminal-integration-test))
