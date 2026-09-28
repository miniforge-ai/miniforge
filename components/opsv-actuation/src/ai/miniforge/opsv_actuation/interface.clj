;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.interface
  "API surface class 1: internal EDN OPSV actuation proposals.
   Preparing a PR does not emit it or confer execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.control :as control]
            [ai.miniforge.opsv-actuation.fences :as fences]
            [ai.miniforge.opsv-actuation.messages :as msg]
            [ai.miniforge.opsv-actuation.execution :as execution]
            [ai.miniforge.opsv-actuation.execution-schema :as execution-schema]
            [ai.miniforge.opsv-actuation.governance :as governance]
            [ai.miniforge.opsv-actuation.proposal :as proposal]
            [ai.miniforge.opsv-actuation.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-mutation-fence
  "Create a runtime-owned, run-local admission handle. Never restore this handle
   from caller input. A stopped handle cannot be reopened; recovery uses separate
   authority. Global N8 integration must stop every active run's handle."
  []
  (fences/create))

(defn ^{:stratum 0} mutation-status
  "Return stopped/in-flight status, or an anomaly for an invalid runtime handle."
  [fence]
  (if (fences/fence? fence)
    (fences/status fence)
    (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {})))

(defn ^{:stratum 0} stop-mutations!
  "Atomically refuse subsequent admission; return the in-flight count.
   Existing operations may settle or become uncertain. This does not revoke
   grants, kill provider requests, or claim that an in-flight effect rolled back."
  [fence]
  (if (fences/fence? fence)
    (fences/stop! fence)
    (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {})))

(defn ^{:stratum 0} at-mutation-boundary!
  "Admit operation once unless stopped; always release its in-flight slot.
   Admission and stop share one atomic state. This runtime handle is a fence,
   not execution authority: operation must still validate current scoped grants."
  [fence operation]
  (if (and (fences/fence? fence) (fn? operation))
    (control/execute! fence operation)
    (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {})))

(defn ^{:stratum 0} prepare-pr
  "Return an evidence-bearing, hashed PR payload, or an input anomaly.
   Failed or incomplete verification forces a draft; no authority is issued.
   Evidence accepts scalar EDN, vectors and string/keyword-keyed maps."
  [input]
  (if-let [errors (m/explain schema/PrProposalInput input)]
    (anomaly/validation-anomaly (msg/ts :proposal/invalid)
                                :opsv/pr-proposal input (me/humanize errors))
    (proposal/prepare-pr input)))

(defn ^{:stratum 0} prepare-governed-pr
  "Prepare the exact provider payload and governance receipt for grant issuance.
   Runtime must include both :pr/payload-hash and :pr/governance-hash in the
   PR grant request. This pure preparation does not confer authority."
  [candidate decision]
  (if (m/validate execution-schema/GovernedArguments [candidate decision])
    (governance/prepare candidate decision)
    (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {})))

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
   Runtime must retain issued-grant-id from issuance/registration independently
   of the effect transaction, not derive it from a reloaded transaction. This
   rejects authority substitution before claiming, even for identical scopes.
   The trusted provider receives [claimed-transaction exact-provider-payload].
   Return the durable transaction or anomaly; uncertain reports stay unknown.
   Clock must return Instant. Runtime must fence this call against emergency
   stop; the grant lookup alone does not lock against concurrent revocation.
   Pass nonblank string paths. Use separate effect and authority directories;
   the authority root must be canonical with no symlinked path components."
  [effect-dir grant-dir id issued-grant-id clock provider]
  (let [args [effect-dir grant-dir id issued-grant-id clock provider]]
    (if (m/validate execution-schema/CommitArguments args)
      (execution/commit! effect-dir grant-dir id issued-grant-id clock provider)
      (anomaly/anomaly :invalid-input (msg/ts :execution/invalid-input) {}))))
