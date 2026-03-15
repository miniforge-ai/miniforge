;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.dedup
  "Deduplicate context items by identity.

   Layer 0 — pure functions, no dependencies.")

(defn dedup-files
  "Deduplicate file entries by path, keeping the first occurrence."
  [files]
  (let [seen (volatile! #{})]
    (filterv (fn [f]
               (let [path (:path f)]
                 (if (contains? @seen path)
                   false
                   (do (vswap! seen conj path) true))))
             files)))

(defn dedup-search-results
  "Deduplicate search results by path, keeping the highest-scoring entry."
  [results]
  (let [by-path (group-by :path results)]
    (->> (vals by-path)
         (mapv (fn [group]
                 (apply max-key :score group)))
         (sort-by :score >)
         vec)))

(defn dedup-files-against-search
  "Remove files that already appear in search results (avoid double-counting)."
  [files search-results]
  (let [search-paths (set (map :path search-results))]
    (filterv #(not (contains? search-paths (:path %))) files)))
