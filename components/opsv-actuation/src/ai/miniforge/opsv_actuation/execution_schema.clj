;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-schema
  "Contracts at the trusted runtime and durable record boundaries."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.proposal :as proposal]
            [ai.miniforge.opsv-actuation.governance :as governance]
            [ai.miniforge.opsv-actuation.schema :as schema])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} content-bound?
  [prepared]
  (and (= (:pr/payload-hash prepared)
          (hash/content-hash (proposal/provider-content prepared)))
       (= (:pr/governance-hash prepared) (governance/digest prepared))))

(defn- ^{:stratum 0} correlated?
  [record]
  (let [prepared (:effect/proposal record)]
    (and (= (:effect/id record) (:effect/id prepared))
         (= (:effect/envelope-id record)
            (get-in prepared [:opsv/envelope :envelope/id])))))

(def ^{:stratum 0} RuntimeInstant [:fn #(instance? Instant %)])

(def ^{:stratum 0} CommitArguments
  [:tuple schema/NonBlankString schema/NonBlankString :uuid :uuid fn? fn?])

(def ^{:stratum 0} AllowingEnvelope
  envelope/AllowingEnvelope)

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ProposeArguments
  [:tuple schema/NonBlankString schema/PrProposalInput :uuid AllowingEnvelope RuntimeInstant])

(def ^{:stratum 1} GovernedArguments [:tuple schema/PrProposalInput AllowingEnvelope])

(def ^{:stratum 1} ClaimedPr
  [:and effect/EffectTransaction
   [:map
    [:effect/class [:= :effect/pr-create]]
    [:effect/state [:= :committing]]
    [:effect/authority [:= :granted]]
    [:effect/grant-id :uuid]
    [:effect/envelope-id :uuid]
    [:effect/proposal
     [:and
      [:map {:closed true}
       [:workflow-run/id :uuid]
       [:effect/id :uuid]
       [:pr/repo schema/NonBlankString]
       [:pr/base schema/NonBlankString]
       [:pr/branch schema/NonBlankString]
       [:pr/head-sha schema/GitObjectId]
       [:pr/title schema/NonBlankString]
       [:pr/body schema/NonBlankString]
       [:pr/draft? :boolean]
       [:pr/payload-hash [:re #"\A[0-9a-f]{64}\z"]]
       [:pr/governance-hash [:re #"\A[0-9a-f]{64}\z"]]
       [:opsv/evidence-bundle-id :uuid]
       [:opsv/envelope AllowingEnvelope]]
      [:fn content-bound?]]]]
   [:fn correlated?]])

(comment
  (envelope/derive-decision [] []))
