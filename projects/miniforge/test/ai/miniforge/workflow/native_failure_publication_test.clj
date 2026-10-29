;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.native-failure-publication-test
  (:require [ai.miniforge.workflow.native-failure-test-support :as support]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [clojure.test :refer [deftest]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-deployment-handlers-preserve-the-caught-exception
  (doseq [phase-name [:deploy :provision :validate]]
    (journal/with-stream (partial support/verify-deployment phase-name))))

(deftest ^{:stratum 0} native-verify-gate-summary-retains-the-recorded-gate-anomaly
  (journal/with-stream support/verify-native-gate))
