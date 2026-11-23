;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-submission-fixtures
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} budget-ms
  "Ordinary disposable creation avoids incidental scheduling expiry."
  5000)

(defn ^{:stratum 0} job [value]
  (kubernetes/build-job-spec "fixture-job" "fixture-namespace"
                             "docker.io/library/alpine:fixture" nil {:VALUE value}
                             {:resources/timeout-seconds 10} "fixture-task"))

(comment (get-in (job "λ") [:metadata :namespace]))
