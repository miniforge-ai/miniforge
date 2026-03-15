;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.context-pack.dedup
  "Deduplicate context items by identity.

   Layer 0 — pure functions, no dependencies.")

(defn- path-not-seen?
  "Returns true if the file's path has not been seen, marking it as seen."
  [seen f]
  (let [path (:path f)]
    (if (contains? @seen path)
      false
      (do (vswap! seen conj path) true))))

(defn dedup-files
  "Deduplicate file entries by path, keeping the first occurrence."
  [files]
  (let [seen (volatile! #{})]
    (filterv (partial path-not-seen? seen) files)))

(defn dedup-search-results
  "Deduplicate search results by path, keeping the highest-scoring entry."
  [results]
  (->> (group-by :path results)
       vals
       (mapv #(apply max-key :score %))
       (sort-by :score >)
       vec))

(defn remove-already-included
  "Remove search results whose paths are already in the included files."
  [included-files search-results]
  (let [included-paths (set (map :path included-files))]
    (filterv #(not (contains? included-paths (:path %))) search-results)))
