;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-pr-events
  "Normalize workflow-owned PR facts, preserving first-seen identity order."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} repo-from-url [url]
  (let [parts (when (string? url) (str/split url #"/"))
        index (some #(when (= "pull" (nth parts % nil)) %) (range (count parts)))]
    (when (and index (>= index 2))
      (str (nth parts (- index 2)) "/" (nth parts (dec index))))))

(defn- ^{:stratum 0} pr-key [pr]
  [(:pr/repo pr) (:pr/number pr)])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} normalize-info [info]
  (let [number (or (:pr-number info) (:pr/id info))
        url (or (:pr-url info) (:pr/url info))
        repo (or (:pr/repo info) (repo-from-url url))]
    (when (and repo number url)
      {:pr/repo repo :pr/number number :pr/url url
       :pr/branch (or (:branch info) (:pr/branch info))
       :pr/title (or (:title info) (:pr/title info))
       :pr/author (or (:author info) (:pr/author info))
       :pr/merge-order (:merge-order info)})))

(defn- ^{:stratum 1} append-unseen [prior pr]
  (if (some #(= (pr-key pr) (pr-key %)) prior) prior (conj prior pr)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} completion-prs [context]
  (let [single (cond-> [] (:workflow/pr-info context) (conj (:workflow/pr-info context)))
        raw (concat (get context :execution/dag-pr-infos []) single)]
    (reduce append-unseen [] (keep normalize-info raw))))

(comment
  (completion-prs {}))
