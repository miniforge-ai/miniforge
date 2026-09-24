;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.interface
  "API surface class 1: internal EDN OPSV actuation proposals.
   Preparing a PR does not emit it or confer execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.messages :as msg]
            [ai.miniforge.opsv-actuation.proposal :as proposal]
            [ai.miniforge.opsv-actuation.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare-pr
  "Return an evidence-bearing, hashed PR payload, or an input anomaly.
   Failed or incomplete verification forces a draft; no authority is issued."
  [input]
  (if-let [errors (m/explain schema/PrProposalInput input)]
    (anomaly/validation-anomaly (msg/ts :proposal/invalid)
                                :opsv/pr-proposal input (me/humanize errors))
    (proposal/prepare-pr input)))
