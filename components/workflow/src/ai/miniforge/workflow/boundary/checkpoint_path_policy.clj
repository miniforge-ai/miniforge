;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-path-policy
  "Validate lexical components before resolving trusted directory aliases."
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [babashka.fs :as fs])
  (:import [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private maximum-link-hops
  "Bound target expansion even when relative cycles keep changing spelling."
  40)

(defn- ^{:stratum 0} existing-components [directory]
  (->> (iterate fs/parent (fs/absolutize directory))
       (take-while some?)
       reverse
       (take-while #(fs/exists? % {:nofollow-links true}))))

(defn- ^{:stratum 0} check-component! [directory leaf? path]
  (if (fs/sym-link? path)
    (policy/check-link! path)
    (policy/check! path (and leaf? (= path directory)))))

(defn- ^{:stratum 0} link-entry [directory leaf? path]
  (when (fs/sym-link? path) [path (and leaf? (= path directory))]))

(defn- ^{:stratum 0} link-target [path]
  (let [target (Files/readSymbolicLink (fs/path path))]
    (if (.isAbsolute target) target (fs/path (fs/parent path) target))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} checked-links [directory leaf?]
  (let [components (vec (existing-components directory))]
    (doseq [path components] (check-component! directory leaf? path))
    (keep (partial link-entry directory leaf?) components)))

(defn- ^{:stratum 1} target-entry [[path leaf?]] [(link-target path) leaf?])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [directory]
  (loop [pending [[(fs/absolutize directory) true]] seen #{}]
    (when-let [[current leaf?] (peek pending)]
      (let [links (remove #(contains? seen (str (first %))) (checked-links current leaf?))
            visited (into seen (map (comp str first)) links)]
        (when (> (count visited) maximum-link-hops)
          (policy/refuse! directory))
        (recur (into (pop pending) (map target-entry) links) visited)))))

(comment ::trusted-original-directory-path)
