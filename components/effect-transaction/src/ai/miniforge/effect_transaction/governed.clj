;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.governed
  "One effect, at most once, under runtime authority.

   A fixed attempt id per effect basis; a grant issued from the closed
   request and registered; a signed allowing envelope; a durable proposal;
   a commit that re-reads the grant from the registry. An earlier attempt
   that did not definitely fail is settled instead of run again, and an
   attempt that ends before its effect ran revokes its grant."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.attempt :as attempt]
   [ai.miniforge.effect-transaction.current-commit :as current]
   [ai.miniforge.effect-transaction.envelope-signature :as envelope-signature]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.reconcile :as reconcile]
   [ai.miniforge.effect-transaction.record :as record]
   [ai.miniforge.execution-grant.interface :as grant])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refusal [message-key data]
  (anomaly/anomaly :unauthorized (msg/t message-key) data))

(defn- ^{:stratum 0} abandon
  "Revoke `issued` and return `result`, noting a revocation that failed."
  [grant-dir issued now result]
  (let [revoked (grant/revoke-stored! grant-dir (:grant/id issued) :revocation/superseded now)]
    (cond-> result
      (and (anomaly/anomaly? result) (anomaly/anomaly? revoked))
      (assoc-in [:anomaly/data :grant/revocation-failure] revoked))))

(defn- ^{:stratum 0} issue!
  "Issue and register a grant for `request`. Registration can fail after
   the record became readable, so a failure revokes the issued id."
  [grant-dir request now]
  (let [issued (grant/issue-for-effect grant-dir request now)
        registered (if (anomaly/anomaly? issued) issued (grant/register! grant-dir issued))]
    (if (and (anomaly/anomaly? registered) (not (anomaly/anomaly? issued)))
      (update registered :anomaly/data assoc :grant/id (:grant/id issued)
              :grant/revocation (grant/revoke-stored! grant-dir (:grant/id issued)
                                                      :revocation/superseded now))
      registered)))

(defn- ^{:stratum 0} settle-existing
  "An earlier attempt's answer: reconciled when still in flight, as it is
   when settled, refused when proposed but never committed."
  [store-dir t probe-fn now]
  (cond
    (contains? #{:committing :unknown-outcome} (:effect/state t))
    (reconcile/reconcile! store-dir t probe-fn now)

    (= :proposed (:effect/state t))
    (anomaly/anomaly :conflict (msg/t :governed/unsettled) {:effect/id (:effect/id t)})

    :else t))

(defn- ^{:stratum 0} effect-scope
  "The proposal the grant must cover: the request's run and target, over
   whatever else the caller records."
  [request proposal]
  (merge proposal (dissoc request :workflow-run/status :effect/class :effect/preflight)))

(defn- ^{:stratum 0} issued-lookup
  "The registered grant this attempt issued; any other id is refused."
  [grant-dir issued-id requested-id]
  (if (= issued-id requested-id)
    (grant/current grant-dir requested-id)
    (anomaly/anomaly :unauthorized (msg/t :governed/grant-substituted)
                     {:grant/id requested-id})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} attempt!
  [{:keys [store-dir grant-dir request envelope proposal effect-fn clock]} effect-id now]
  (let [request (assoc request :effect/id effect-id)
        issued (issue! grant-dir request now)
        scope (effect-scope request proposal)
        authorized? (and (not (anomaly/anomaly? issued))
                         (grant/authorized? (grant/authorize issued {:effect/scope scope
                                                                     :usage/count 1}
                                                             now)))
        allowing? (contains? envelope-signature/allowing-decisions
                             (:envelope/decision envelope))
        proposed (when (and authorized? allowing?)
                   (record/propose! store-dir
                                    {:effect-id effect-id :effect-class (:effect/class request)
                                     :grant-id (:grant/id issued)
                                     :envelope-id (:envelope/id envelope)
                                     :envelope (envelope-signature/sign envelope)
                                     :proposal (dissoc scope :effect/id)}
                                    now))
        committed (when (and proposed (not (anomaly/anomaly? proposed)))
                    (current/commit! store-dir effect-id
                                     (partial issued-lookup grant-dir (:grant/id issued))
                                     (or clock #(Instant/now)) effect-fn))]
    (cond
      (anomaly/anomaly? issued) issued
      (not authorized?) (abandon grant-dir issued now (refusal :governed/unauthorized {}))
      (not allowing?) (abandon grant-dir issued now (refusal :governed/envelope-refuses {}))
      (anomaly/anomaly? proposed) (abandon grant-dir issued now proposed)
      (anomaly/anomaly? committed) (abandon grant-dir issued now committed)
      (= :failed (:effect/state committed)) (abandon grant-dir issued now committed)
      :else committed)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} govern!
  "Run one effect at most once under a registered grant and a signed
   allowing envelope; return its record or an anomaly. `opts`:
   `:store-dir` and `:grant-dir`; `:basis`, what fixes the effect id;
   `:request`, the closed grant request without `:effect/id`; `:envelope`,
   the decision minted for this effect; `:proposal`, extra fields the
   record carries; `:effect-fn`, called with the committing record;
   `:probe-fn`, asked about an earlier attempt still in flight; optional
   `:clock`."
  [{:keys [store-dir basis probe-fn] :as opts} ^Instant now]
  (let [opened (attempt/open store-dir basis)]
    (cond
      (anomaly/anomaly? opened) opened
      (:effect/existing opened) (settle-existing store-dir (:effect/existing opened) probe-fn now)
      :else (attempt! opts (:effect/id opened) now))))

(comment
  (govern! {} (Instant/now)))
