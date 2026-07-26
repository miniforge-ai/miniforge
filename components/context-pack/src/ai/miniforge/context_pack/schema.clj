;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.context-pack.schema
  "Malli schemas for context-pack domain types.

   No dependencies on other context-pack namespaces. ContextPack
   references Source in this file, so it sits one same-file layer above
   Source and BudgetAudit — see the per-def :stratum metadata for the
   real breakdown.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Source
  [:map
   [:kind [:enum :repo-map :file :search]]
   [:path [:maybe [:string {:min 1}]]]
   [:tokens :int]])

(def ^{:stratum 0} BudgetAudit
  [:map
   [:phase :keyword]
   [:budget :int]
   [:tokens-used :int]
   [:tokens-remaining :int]
   [:exhausted? :boolean]
   [:source-count :int]
   [:utilization :double]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ContextPack
  [:map
   [:phase :keyword]
   [:budget :int]
   [:tokens-used :int]
   [:repo-map [:maybe :string]]
   [:files [:vector :map]]
   [:search-results [:vector :map]]
   [:exhausted? :boolean]
   [:sources [:vector Source]]])
