;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.control-action
  "Nested N6 action evidence, including N8 approval vocabulary."
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Requester
  [:map [:principal :string] [:listener-id :uuid]
   [:capability {:optional true} [:= :control]]])

(def ^{:stratum 0} Result
  [:map [:status :keyword]
   [:error {:optional true} :map]
   [:executed-at {:optional true} inst?]])

(def ^{:stratum 0} Approval
  [:and
   [:map
    [:status {:optional true} :keyword]
    [:approval-status {:optional true} [:enum :pending :approved :rejected]]
    [:required-approvers {:optional true} nat-int?]
    [:approvers [:vector [:map [:principal :string] [:timestamp inst?] [:decision :keyword]]]]]
   [:fn #(= 1 (count (set (vals (select-keys % [:status :approval-status])))))]])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} requester? [value] (schema/valid? Requester value))

(defn ^{:stratum 1} result? [value] (schema/valid? Result value))

(defn ^{:stratum 1} approval? [value] (schema/valid? Approval value))

(comment
  (result? {:status :pending}))
