;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.context-pack.interface
  "Public API for the context-pack component.

   Assembles bounded, auditable context documents from repo-index data
   with per-phase token budgets.

   Sections: schema re-exports, factory re-exports, budget queries,
   pack building and auditing. All delegate to other namespaces, so
   there's no same-file reference depth between them — every def here
   sits at Layer 0."
  (:require [ai.miniforge.context-pack.schema :as schema]
            [ai.miniforge.context-pack.factory :as factory]
            [ai.miniforge.context-pack.builder :as builder]
            [ai.miniforge.context-pack.budget :as budget]
            [ai.miniforge.context-pack.config :as config]))

;------------------------------------------------------------------------------ Layer 0

;; Schema re-exports
(def ^{:stratum 0} ContextPack
  "Malli schema (a [:map ...] vector) for a bounded context pack.

   Keys: :phase (keyword), :budget (int), :tokens-used (int),
   :repo-map (string or nil), :files (vector of maps),
   :search-results (vector of maps), :exhausted? (boolean),
   :sources (vector of Source)."
  schema/ContextPack)

(def ^{:stratum 0} BudgetAudit
  "Malli schema (a [:map ...] vector) for a budget audit snapshot.

   Keys: :phase (keyword), :budget (int), :tokens-used (int),
   :tokens-remaining (int), :exhausted? (boolean), :source-count (int),
   :utilization (double)."
  schema/BudgetAudit)

(def ^{:stratum 0} Source
  "Malli schema (a [:map ...] vector) for a single source-tracking entry.

   Keys: :kind (enum :repo-map | :file | :search), :path (non-empty
   string or nil), :tokens (int)."
  schema/Source)

;; Factory re-exports
(def ^{:stratum 0} ->context-pack
  "Construct a ContextPack map with an empty audit trail.

   Args: phase (keyword), budget (int), repo-map-text (string or nil),
   files (vector of maps), search-results (vector of maps).
   Returns a ContextPack map; :tokens-used 0, :exhausted? false,
   :sources []. Total function, never throws."
  factory/->context-pack)

(def ^{:stratum 0} ->budget-audit
  "Construct a BudgetAudit snapshot map.

   Args: phase (keyword), budget (int), tokens-used (int),
   exhausted? (boolean), source-count (int). `budget` and `tokens-used`
   must be numeric — they feed arithmetic (`(- budget tokens-used)` and
   `(/ tokens-used budget)`) that throws on nil or non-numeric input.
   Returns a BudgetAudit map; :tokens-remaining clamped at >= 0,
   :utilization is tokens-used/budget as a double (0.0 when budget
   is non-positive)."
  factory/->budget-audit)

(def ^{:stratum 0} ->source
  "Construct a Source-tracking entry for a pack's audit trail.

   Args: kind (keyword :repo-map | :file | :search), path (string or
   nil), tokens (int). Returns a Source map. Total function, never
   throws."
  factory/->source)

(def ^{:stratum 0} ->pack-context
  "Construct a pack-context map for caching context-pack results in an
   execution context.

   Args: repo-index (RepoIndex map), context-pack (ContextPack map).
   Returns a map with :repo-index, :context-pack, :repo-map-text (the
   pack's :repo-map) and :existing-files (the pack's :files). Total
   function, never throws."
  factory/->pack-context)

;; Budget queries
(defn ^{:stratum 0} phase-budget
  "Get the configured token budget for a phase."
  [phase]
  (config/phase-budget phase))

(defn ^{:stratum 0} tokens-remaining
  "Get remaining token budget for a context pack."
  [pack]
  (budget/tokens-remaining pack))

(defn ^{:stratum 0} exhausted?
  "Check if a pack's budget is exhausted."
  [pack]
  (budget/exhausted? pack))

;; Pack building
(defn ^{:stratum 0} build-pack
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

(defn ^{:stratum 0} extend-pack
  "Extend an existing context pack with additional content.

   Arguments:
   - pack       - Existing ContextPack
   - repo-index - RepoIndex map
   - opts       - Map with :files-in-scope and/or :search-index + :search-query

   Returns:
   - Updated ContextPack (budget still enforced)"
  [pack repo-index opts]
  (builder/extend-pack pack repo-index opts))

(defn ^{:stratum 0} audit
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
