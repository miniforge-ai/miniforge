;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.interface
  "API surface class 1: internal EDN OPSV actuation proposals.
   Preparing a PR does not emit it or confer execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.messages :as msg]
            [ai.miniforge.opsv-actuation.execution :as execution]
            [ai.miniforge.opsv-actuation.execution-schema :as execution-schema]
            [ai.miniforge.opsv-actuation.proposal :as proposal]
            [ai.miniforge.opsv-actuation.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare-pr
  "Return an evidence-bearing, hashed PR payload, or an input anomaly.
   Failed or incomplete verification forces a draft; no authority is issued.
   Evidence accepts scalar EDN, vectors and string/keyword-keyed maps."
  [input]
  (if-let [errors (m/explain schema/PrProposalInput input)]
    (anomaly/validation-anomaly (msg/ts :proposal/invalid)
                                :opsv/pr-proposal input (me/humanize errors))
    (proposal/prepare-pr input)))

(defn ^{:stratum 0} propose-pr!
  "Persist a prepared PR, evidence join and allowing runtime envelope.
   Runtime must supply the evaluated envelope and matching registered grant ID;
   this API neither issues authority nor accepts model-supplied decisions.
   Unfulfilled obligations are refused. Duplicate IDs never replace a proposal.
   The envelope timestamp is stored at EDN #inst millisecond precision."
  [dir candidate grant-id decision now]
  (let [args [dir candidate grant-id decision now]]
    (if (m/validate execution-schema/ProposeArguments args)
      (execution/propose! dir candidate grant-id decision now)
      (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {}))))

(defn ^{:stratum 0} commit-pr!
  "Commit by durable ID using current registered authority, never a snapshot.
   The trusted provider receives [claimed-transaction exact-provider-payload].
   Return the durable transaction or anomaly; uncertain reports stay unknown.
   Clock must return Instant. Runtime must fence this call against emergency
   stop; the grant lookup alone does not lock against concurrent revocation."
  [effect-dir grant-dir id clock provider]
  (let [args [effect-dir grant-dir id clock provider]]
    (if (m/validate execution-schema/CommitArguments args)
      (execution/commit! effect-dir grant-dir id clock provider)
      (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {}))))
