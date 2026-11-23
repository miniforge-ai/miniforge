;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.opsv-finalization-integration-test
  (:require [ai.miniforge.workflow.checkpoint-root-support :as checkpoint]
            [ai.miniforge.workflow.isolation-support :as isolation]
            [ai.miniforge.workflow.opsv-finalization-support :as support]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [clojure.test :refer [deftest use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-current-root [root]
  (journal/with-stream (partial support/run-current! root)))

(deftest ^{:stratum 0} shared-workflow-runner-persists-finalized-opsv-evidence-test
  (checkpoint/call-with-temp-checkpoint-root (partial support/run-legacy! false)))

(deftest ^{:stratum 0} failed-publication-recovers-from-shared-workflow-checkpoint-test
  (checkpoint/call-with-temp-checkpoint-root (partial support/run-legacy! true)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} full-opsv-run-persists-current-lifecycle-and-evidence
  (checkpoint/call-with-temp-checkpoint-root with-current-root))

(use-fixtures :once isolation/with-isolated-host)

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.opsv-finalization-integration-test))
