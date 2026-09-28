;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.schema
  "Runtime configuration and exact authorized provider-call contracts."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-provider-github.wire :as wire]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlank [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} Repository [:re #"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*"])

(def ^{:stratum 0} GitObjectId [:re #"(?:[0-9a-f]{40}|[0-9a-f]{64})"])

(defn- ^{:stratum 0} content-bound? [[_ record payload]]
  (and (= payload (select-keys (:effect/proposal record) wire/payload-keys))
       (= (hash/content-hash payload) (get-in record [:effect/proposal :pr/payload-hash]))))

(def ^{:stratum 0} AuthorizedPr
  [:and effect/EffectTransaction
   [:map
    [:effect/class [:= :effect/pr-create]]
    [:effect/authority [:= :granted]]
    [:effect/grant-id :uuid]
    [:effect/envelope-id :uuid]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ProviderRuntime
  [:map {:closed true}
   [:directory NonBlank]
   [:hostname [:re #"[A-Za-z0-9][A-Za-z0-9.-]*"]]
   [:run-command fn?]])

(def ^{:stratum 1} Payload
  [:map {:closed true}
   [:pr/repo Repository]
   [:pr/base NonBlank]
   [:pr/branch NonBlank]
   [:pr/head-sha GitObjectId]
   [:pr/title NonBlank]
   [:pr/body NonBlank]
   [:pr/draft? :boolean]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} CreateArguments
  [:and [:tuple ProviderRuntime
         [:and AuthorizedPr [:map [:effect/state [:= :committing]]]] Payload]
   [:fn content-bound?]])

(def ^{:stratum 2} ObserveArguments
  [:and [:tuple ProviderRuntime
         [:and AuthorizedPr [:map [:effect/state [:enum :committing :unknown-outcome]]]] Payload]
   [:fn content-bound?]])

(comment
  Repository)
