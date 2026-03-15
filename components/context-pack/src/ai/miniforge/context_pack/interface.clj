;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.interface
  "Public API for the context-pack component.

   Assembles bounded, auditable context documents from repo-index data
   with per-phase token budgets.

   Layer 0: Schema re-exports
   Layer 1: Budget queries
   Layer 2: Pack building and auditing"
  (:require [ai.miniforge.context-pack.schema :as schema]
            [ai.miniforge.context-pack.builder :as builder]
            [ai.miniforge.context-pack.budget :as budget]
            [ai.miniforge.context-pack.config :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def ContextPack schema/ContextPack)
(def BudgetAudit schema/BudgetAudit)
(def Source schema/Source)

;------------------------------------------------------------------------------ Layer 1
;; Budget queries

(defn phase-budget
  "Get the configured token budget for a phase."
  [phase]
  (config/phase-budget phase))

(defn tokens-remaining
  "Get remaining token budget for a context pack."
  [pack]
  (budget/tokens-remaining pack))

(defn exhausted?
  "Check if a pack's budget is exhausted."
  [pack]
  (budget/exhausted? pack))

;------------------------------------------------------------------------------ Layer 2
;; Pack building

(defn build-pack
  "Assemble a context pack for a given phase.

   Wraps repo-map + files + search results into a bounded context
   document with per-phase token budgets.

   Arguments:
   - phase        - Phase keyword (:plan, :implement, :test, :review)
   - repo-index   - RepoIndex map (from repo-index/build-index)
   - opts         - Optional map:
     - :files-in-scope - Seq of file paths to include
     - :search-index   - SearchIndex (from repo-index/build-search-index)
     - :search-query   - Query string for lexical search
     - :budget         - Override token budget

   Returns:
   - ContextPack map"
  [phase repo-index opts]
  (builder/build-pack phase repo-index opts))

(defn extend-pack
  "Extend an existing context pack with additional content.

   Arguments:
   - pack       - Existing ContextPack
   - repo-index - RepoIndex map
   - opts       - Map with :files-in-scope and/or :search-index + :search-query

   Returns:
   - Updated ContextPack (budget still enforced)"
  [pack repo-index opts]
  (builder/extend-pack pack repo-index opts))

(defn audit
  "Create a budget audit snapshot for a context pack.

   Returns:
   - BudgetAudit map with :budget, :tokens-used, :tokens-remaining,
     :utilization, :exhausted?, :source-count"
  [pack]
  (builder/audit pack))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.repo-index.interface :as ri])
  (def idx (ri/build-index "."))

  ;; Build a pack for implement phase
  (def pack (build-pack :implement idx
              {:files-in-scope ["deps.edn" "workspace.edn"]}))
  (:tokens-used pack)
  (:exhausted? pack)
  (count (:files pack))
  (audit pack)

  ;; Extend with search
  (def si (ri/build-search-index idx))
  (def pack2 (extend-pack pack idx
               {:search-index si :search-query "implement phase"}))
  (audit pack2)

  :leave-this-here)
