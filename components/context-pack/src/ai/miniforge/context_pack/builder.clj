;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.builder
  "Assemble context packs from repo-index data.

   Layer 2 — depends on budget, dedup, config, factory."
  (:require [ai.miniforge.context-pack.budget :as budget]
            [ai.miniforge.context-pack.config :as config]
            [ai.miniforge.context-pack.dedup :as dedup]
            [ai.miniforge.context-pack.factory :as factory]
            [ai.miniforge.repo-index.interface :as repo-index]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- snippet-text
  "Extract concatenated text from a search hit's snippets."
  [hit]
  (apply str (map :text (:snippets hit))))

(defn- reduce-within-budget
  "Reduce items into a pack, stopping when budget is exhausted.
   item-fn takes (pack, item) and returns updated pack."
  [pack items item-fn]
  (reduce
    (fn [p item]
      (if (budget/exhausted? p)
        (reduced p)
        (item-fn p item)))
    pack
    items))

;------------------------------------------------------------------------------ Layer 1
;; Pack assembly steps

(defn- add-repo-map
  "Add the repo map to the pack, consuming budget."
  [pack repo-index]
  (if (or (nil? repo-index) (budget/exhausted? pack))
    pack
    (let [map-budget (min (config/repo-map-budget) (budget/tokens-remaining pack))
          rmap (repo-index/repo-map-text repo-index {:token-budget map-budget})]
      (-> pack
          (assoc :repo-map rmap)
          (budget/add-source :repo-map nil rmap)))))

(defn- try-add-file
  "Try to add a single file to the pack within budget."
  [pack file]
  (budget/try-add-item pack :file (:path file) (:content file)
    #(update % :files conj file)))

(defn- try-add-search-hit
  "Try to add a single search hit to the pack within budget."
  [pack hit]
  (budget/try-add-item pack :search (:path hit) (snippet-text hit)
    #(update % :search-results conj hit)))

(defn- load-files
  "Load and deduplicate files from the repo index."
  [repo-index files-in-scope]
  (let [max-f (config/max-files)
        max-l (config/max-lines-per-file)]
    (->> (repo-index/get-files repo-index (take max-f files-in-scope)
                               {:max-lines max-l})
         dedup/dedup-files)))

(defn- load-search-results
  "Load, deduplicate, and filter search results against already-included files."
  [search-index query included-files]
  (let [max-r (config/max-search-results)]
    (->> (repo-index/search-lex search-index query {:max-results max-r})
         dedup/dedup-search-results
         (dedup/remove-already-included included-files))))

(defn- add-files
  "Add file contents to the pack within budget."
  [pack repo-index files-in-scope]
  (if (or (not (seq files-in-scope)) (budget/exhausted? pack))
    pack
    (reduce-within-budget pack (load-files repo-index files-in-scope) try-add-file)))

(defn- add-search-results
  "Add search results to the pack within budget."
  [pack search-index query]
  (if (or (nil? search-index) (nil? query) (budget/exhausted? pack))
    pack
    (let [filtered (load-search-results search-index query (:files pack))]
      (reduce-within-budget pack filtered try-add-search-hit))))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn build-pack
  "Assemble a context pack for a given phase.

   Arguments:
   - phase        - Phase keyword (:plan, :implement, :test, :review)
   - repo-index   - RepoIndex map (from repo-index/build-index)
   - opts         - Optional map:
     - :files-in-scope - Seq of file paths to include
     - :search-index   - SearchIndex (from repo-index/build-search-index)
     - :search-query   - Query string for lexical search
     - :budget         - Override token budget (default from config)

   Returns:
   - ContextPack map with :repo-map, :files, :search-results, :budget, etc."
  [phase repo-index opts]
  (let [budget-tokens (or (:budget opts) (config/phase-budget phase))
        pack (factory/->context-pack phase budget-tokens nil [] [])]
    (-> pack
        (add-repo-map repo-index)
        (add-files repo-index (:files-in-scope opts))
        (add-search-results (:search-index opts) (:search-query opts)))))

(defn extend-pack
  "Extend an existing context pack with additional files or search results."
  [pack repo-index opts]
  (-> pack
      (add-files repo-index (:files-in-scope opts))
      (add-search-results (:search-index opts) (:search-query opts))))

(defn audit
  "Create a budget audit snapshot for a context pack."
  [pack]
  (factory/->budget-audit
    (:phase pack)
    (:budget pack)
    (:tokens-used pack)
    (:exhausted? pack)
    (count (:sources pack))))
