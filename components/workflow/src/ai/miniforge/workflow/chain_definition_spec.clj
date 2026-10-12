;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-definition-spec
  "Admission schemas for the deployed sequential definition vocabulary."
  (:require [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.chain-composition :as composition]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Binding
  [:or :string :keyword [:vector [:or :keyword :string :int]]])

(def ^{:stratum 0} ResolvedVersion
  [:and schema/NonBlankString [:not [:= "latest"]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Step
  [:map {:closed true}
   [:step/id :keyword]
   [:step/workflow-id :keyword]
   [:step/input-bindings {:optional true} [:maybe [:map-of :keyword Binding]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Definition
  [:and
   [:map {:closed true}
    [:chain/id :keyword]
    [:chain/version ResolvedVersion]
    [:chain/description {:optional true} :string]
    [:chain/steps [:vector Step]]]
   [:fn composition/unique-step-ids?]])

(comment
  Definition)
