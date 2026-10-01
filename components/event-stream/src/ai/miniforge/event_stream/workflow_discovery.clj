;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-discovery
  "Discover workflow identities across canonical and legacy storage layouts."
  (:require [ai.miniforge.event-stream.storage-layout :as layout]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} directory-names [parent]
  (->> (.listFiles (io/file parent))
       (filter #(.isDirectory ^java.io.File %))
       (map #(.getName ^java.io.File %))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} workflow-ids [base-dir]
  (let [canonical [(layout/live-subdir) (layout/archived-subdir)]
        reserved (conj (set canonical) (layout/operator-subdir))
        roots (map #(io/file base-dir %) canonical)
        legacy (remove reserved (directory-names base-dir))]
    (->> (concat legacy (mapcat directory-names roots)) distinct sort vec)))

(comment
  ::workflow-ids)
