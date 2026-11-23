;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-mountinfo
  "Select only an unambiguous, supported mount from the process mount table."
  (:require [ai.miniforge.file-durability.boundary.linux-mountinfo-row :as row]
            [clojure.string :as str])
  (:import [java.nio.file Path]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} closest-target [^Path root mounts]
  (->> mounts
       (keep :mount/target)
       (filter #(.startsWith root ^Path %))
       (sort-by #(.getNameCount ^Path %))
       last))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matching-mount
  "Return the most specific mount only when its identity is unambiguous."
  [^Path root contents]
  (let [mounts (mapv row/parse (str/split-lines contents))
        closest (closest-target root mounts)
        matches (filter #(= closest (:mount/target %)) mounts)
        entry (first matches)]
    (when (and closest (not-any? nil? mounts) (= 1 (count matches))
               (row/writable-local-type? entry))
      entry)))

(comment (matching-mount (java.nio.file.Path/of "/" (make-array String 0)) ""))
