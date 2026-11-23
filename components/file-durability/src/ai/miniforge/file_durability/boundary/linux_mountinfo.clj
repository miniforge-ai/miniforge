;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-mountinfo
  "Select only an unambiguous, supported mount from the process mount table."
  (:require [ai.miniforge.file-durability.boundary.linux-mountinfo-row :as row]
            [clojure.string :as str])
  (:import [java.nio.file Path]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} covering-mounts [^Path root mounts]
  (->> mounts
       (filter :mount/target)
       (filter #(.startsWith root ^Path (:mount/target %)))
       (sort-by #(.getNameCount ^Path (:mount/target %)))))

(defn- ^{:stratum 0} attached? [[parent child]]
  (and (not= (:mount/target parent) (:mount/target child))
       (= (:mount/id parent) (:mount/parent child))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} visible-chain? [covering]
  (and (= (count covering) (count (set (map :mount/id covering))))
       (every? attached? (partition 2 1 covering))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} matching-mount
  "Return the most specific mount only when its identity is unambiguous."
  [^Path root contents]
  (let [mounts (mapv row/parse (str/split-lines contents))
        covering (covering-mounts root mounts)
        closest (:mount/target (last covering))
        matches (filter #(= closest (:mount/target %)) covering)
        entry (first matches)]
    (when (and closest (not-any? nil? mounts) (= 1 (count matches))
               (visible-chain? covering)
               (row/writable-local-type? entry))
      entry)))

(comment (matching-mount (java.nio.file.Path/of "/" (make-array String 0)) ""))
