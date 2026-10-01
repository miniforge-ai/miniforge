;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.actuation-snapshot
  "Bind successful actuation output to its preflight evidence base across restarts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.evidence-base :as base]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} captured [ctx output]
  (let [evidence-base (base/bundle ctx)]
    {:opsv/evidence-base evidence-base :opsv/actuation-output output}))

(defn- ^{:stratum 0} restored-context [ctx record]
  (-> ctx
      (assoc-in [:execution/opts :opsv/evidence-base] (:opsv/evidence-base record))
      (assoc :opsv/recovered-actuation-output (:opsv/actuation-output record))))

(defn- ^{:stratum 0} valid-record? [ctx record]
  (let [bundle (:opsv/evidence-base record)]
    (and (map? (:opsv/actuation-output record))
         (:valid? (evidence/validate-canonical-bundle bundle))
         (= (context/workflow-id ctx) (:evidence-bundle/workflow-id bundle))
         (= (get-in ctx [:execution/input :opsv/evidence-bundle-id]) (:evidence-bundle/id bundle)))))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :evidence/assembly-mismatch) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} encode [ctx output]
  (snapshot/encode ctx :post-actuation (captured ctx output)))

(defn ^{:stratum 1} restore-context [ctx encoded]
  (let [record (snapshot/decode ctx :post-actuation encoded)]
    (cond
      (anomaly/anomaly? record) record
      (valid-record? ctx record) (restored-context ctx record)
      :else (invalid))))

(comment
  (restore-context {} "invalid"))
