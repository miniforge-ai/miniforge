;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.restored-failure-test
  (:require [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.restored-failure-support :as support]
            [clojure.test :refer [deftest]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-journal [root]
  (journal/with-stream (partial support/verify-restored root)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} restored-native-failure-retains-attempt-identity-and-diagnostics
  (fixture/call-with-temp-checkpoint-root with-journal))
