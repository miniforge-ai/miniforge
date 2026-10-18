;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-run-spec
  "The durable admission contract, not a resumable execution-machine state."
  (:require [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-definition-spec :as definition]
            [ai.miniforge.workflow.chain-run-record :as record]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Snapshot
  [:and
   [:map {:closed true}
    [:chain/definition-id :keyword]
    [:chain/definition-version definition/ResolvedVersion]
    [:chain/description {:optional true} :string]
    [:chain/steps [:vector definition/Step]]]
   [:fn composition/unique-step-ids?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Record
  [:and
   [:map {:closed true}
    [:chain/run-id :uuid]
    [:chain/definition-id :keyword]
    [:chain/definition-version definition/ResolvedVersion]
    [:chain/definition-snapshot Snapshot]]
   [:fn record/identity-matches?]])

(comment
  Record)
