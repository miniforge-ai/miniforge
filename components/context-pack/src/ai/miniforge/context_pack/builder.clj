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

(defn- add-files
  "Add file contents to the pack within budget."
  [pack repo-index files-in-scope]
  (if (or (not (seq files-in-scope)) (budget/exhausted? pack))
    pack
    (let [max-f (config/max-files)
          max-l (config/max-lines-per-file)
          files (repo-index/get-files repo-index
                                      (take max-f files-in-scope)
                                      {:max-lines max-l})
          deduped (dedup/dedup-files files)]
      (reduce
        (fn [p file]
          (if (budget/exhausted? p)
            (reduced p)
            (let [tokens (budget/estimate-tokens (:content file))
                  would-exhaust? (> (+ (:tokens-used p) tokens) (:budget p))]
              (if would-exhaust?
                (reduced (assoc p :exhausted? true))
                (-> p
                    (update :files conj file)
                    (budget/add-source :file (:path file) (:content file)))))))
        pack
        deduped))))

(defn- add-search-results
  "Add search results to the pack within budget."
  [pack search-index query]
  (if (or (nil? search-index) (nil? query) (budget/exhausted? pack))
    pack
    (let [max-r (config/max-search-results)
          results (repo-index/search-lex search-index query {:max-results max-r})
          deduped (dedup/dedup-search-results results)
          ;; Remove files already included directly
          filtered (dedup/dedup-files-against-search deduped (:files pack))]
      (reduce
        (fn [p hit]
          (if (budget/exhausted? p)
            (reduced p)
            (let [snippet-text (->> (:snippets hit) (map :text) (apply str))
                  tokens (budget/estimate-tokens snippet-text)
                  would-exhaust? (> (+ (:tokens-used p) tokens) (:budget p))]
              (if would-exhaust?
                (reduced (assoc p :exhausted? true))
                (-> p
                    (update :search-results conj hit)
                    (budget/add-source :search (:path hit) snippet-text))))))
        pack
        filtered))))

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
  "Extend an existing context pack with additional files or search results.

   Arguments:
   - pack         - Existing ContextPack
   - repo-index   - RepoIndex map
   - opts         - Map with :files-in-scope and/or :search-index + :search-query

   Returns:
   - Updated ContextPack with additional content (budget enforced)"
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
