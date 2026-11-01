;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify.result
  "Construct the shared parsed-test result shape.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} summary [passed? tests assertions failures errors output]
  {:passed? passed?
   :test-count tests
   :assertion-count assertions
   :fail-count failures
   :error-count errors
   :output output})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} test-error-result [message]
  (summary false 0 0 0 1 message))

(comment ::test-result-values)
