;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-emission
  "Keep recommendations unless runtime policy and configured execution permit PRs."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.pr-outcome :as outcome]
            [ai.miniforge.phase-opsv.pr-runtime :as runtime]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} eligible? [output]
  (let [record (:opsv/actuation-record output)]
    (and (contains? #{:pr-only :apply-allowed} (:requested-actuation-mode record))
         (not= :none (:effective-actuation-mode record))
         (= :allow (get-in output [:opsv/decision-envelope :envelope/decision]))
         (every? :gate/passed? (:opsv/gate-results output)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} emit! [ctx output]
  (let [configured (get-in ctx [:execution/opts :opsv/pr-execution])]
    (if (and (not (anomaly/anomaly? output)) configured (eligible? output))
      (outcome/attach output (runtime/execute! configured ctx output))
      output)))

(comment
  (eligible? {}))
