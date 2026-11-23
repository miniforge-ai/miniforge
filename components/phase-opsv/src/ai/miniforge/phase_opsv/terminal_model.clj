;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-model
  "Pure classification and result projections for retained terminal actuation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.pr-audit-model :as audit-model]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} retained-output [failure]
  (get-in failure [:anomaly/data :opsv/phase-output]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retain-publication [output failure]
  (merge (audit-model/retain output failure)
         (select-keys (retained-output failure) [:opsv/phase-produced-artifact-refs])))

(defn ^{:stratum 1} recoverable? [failure]
  (let [output (retained-output failure)]
    (and (anomaly/any-anomaly? failure)
         (map? (:opsv/actuation-record output))
         (map? (:opsv/phase-failure output)))))

(defn ^{:stratum 1} classify-failure [failure]
  (let [output (retained-output failure)]
    (if (and (anomaly/any-anomaly? failure) (map? (:opsv/actuation-record output)))
      (assoc-in failure [:anomaly/data :opsv/phase-output :opsv/phase-failure]
                (get output :opsv/phase-failure (select-keys failure [:anomaly/type :anomaly/message])))
      failure)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attach-evidence [failure published]
  (if (anomaly/any-anomaly? published)
    (-> failure
        (assoc-in [:anomaly/data :opsv/evidence-failure] published)
        (update-in [:anomaly/data :opsv/phase-output] retain-publication published))
    (assoc-in failure [:anomaly/data :opsv/phase-output] published)))

(comment
  (recoverable? {}))
