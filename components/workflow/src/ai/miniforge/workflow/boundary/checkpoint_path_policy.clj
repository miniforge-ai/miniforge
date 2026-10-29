;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-path-policy
  "Validate lexical components before resolving trusted directory aliases."
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} existing-components [directory]
  (->> (iterate fs/parent (fs/absolutize directory))
       (take-while some?)
       reverse
       (take-while #(fs/exists? % {:nofollow-links true}))))

(defn- ^{:stratum 0} check-component! [directory path]
  (if (fs/sym-link? path)
    (policy/check-link! path)
    (policy/check! path (= path directory))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check! [directory]
  (let [absolute (fs/absolutize directory)]
    (doseq [path (existing-components absolute)]
      (check-component! absolute path))))

(comment ::trusted-original-directory-path)
