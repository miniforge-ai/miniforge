;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.schema
  "Malli schemas for context-pack domain types.

   Layer 0 — pure data definitions.")

;------------------------------------------------------------------------------ Layer 0

(def Source
  "Schema for a source-tracking entry."
  [:map
   [:kind [:enum :repo-map :file :search]]
   [:path [:maybe [:string {:min 1}]]]
   [:tokens :int]])

(def ContextPack
  "Schema for a bounded context pack."
  [:map
   [:phase :keyword]
   [:budget :int]
   [:tokens-used :int]
   [:repo-map [:maybe :string]]
   [:files [:vector :map]]
   [:search-results [:vector :map]]
   [:exhausted? :boolean]
   [:sources [:vector Source]]])

(def BudgetAudit
  "Schema for a budget audit snapshot."
  [:map
   [:phase :keyword]
   [:budget :int]
   [:tokens-used :int]
   [:tokens-remaining :int]
   [:exhausted? :boolean]
   [:source-count :int]
   [:utilization :double]])
