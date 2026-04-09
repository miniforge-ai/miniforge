;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.repo-analyzer
  "Analyze a repository to detect technologies and select policy packs.

   All detection rules are data-driven from tech-fingerprints.edn.
   Each fingerprint declares extensions, file/directory markers, optional
   content-match patterns, and applicable policy packs.

   Layer 0: Fingerprint loading and extension index
   Layer 1: Detection (extensions + markers)
   Layer 2: Pack selection and full analysis"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Fingerprint loading

(def ^:private fingerprints-resource "config/cli/tech-fingerprints.edn")

(def ^:private always-packs
  "Packs that apply to every repository."
  ["foundations-1.0.0"])

(def fingerprints
  "Technology fingerprints loaded from classpath EDN."
  (delay
    (if-let [url (io/resource fingerprints-resource)]
      (edn/read-string (slurp url))
      [])))

(defn- build-extension-index
  "Build a map of file extension → tech ID from fingerprints."
  [fps]
  (reduce (fn [idx {:keys [tech/id tech/extensions]}]
            (reduce #(assoc %1 %2 id) idx (or extensions [])))
          {}
          fps))

(defn- file-extension [path]
  (let [filename (str (fs/file-name path))]
    (when-let [idx (str/last-index-of filename ".")]
      (subs filename (inc idx)))))

;------------------------------------------------------------------------------ Layer 1
;; Detection

(defn detect-languages
  "Detect technologies by file extension. Returns a set of tech IDs."
  [repo-path]
  (let [ext-idx (build-extension-index @fingerprints)
        files   (fs/glob repo-path "**" {:hidden false})]
    (->> files
         (keep (fn [f]
                 (when (fs/regular-file? f)
                   (get ext-idx (file-extension (str f))))))
         (into #{}))))

(defn- marker-present?
  "Check if a single marker is present in the repo."
  [repo-path {:keys [path type content]}]
  (let [target (fs/path repo-path path)]
    (cond
      ;; Glob-style path with content match
      (and content (str/includes? path "*"))
      (some (fn [f]
              (when (fs/regular-file? f)
                (try (str/includes? (slurp (str f)) content)
                     (catch Exception _ false))))
            (fs/glob repo-path path {:hidden false}))

      ;; Directory check
      (= type :directory)
      (fs/directory? target)

      ;; Simple file existence
      (str/includes? path "*")
      (seq (fs/glob repo-path path {:hidden false}))

      :else
      (fs/exists? target))))

(defn detect-markers
  "Detect technologies by file/directory markers. Returns a set of tech IDs."
  [repo-path]
  (let [fps (filter :tech/markers @fingerprints)]
    (->> fps
         (filter (fn [{:keys [tech/markers]}]
                   (some #(marker-present? repo-path %) markers)))
         (map :tech/id)
         (into #{}))))

(defn detect-git-host
  "Detect git host from remote URL."
  [repo-path]
  (try
    (let [url (:out (process/sh {:cmd ["git" "remote" "get-url" "origin"]
                                    :dir (str repo-path)
                                    :continue true
                                    :out :string :err :string}))]
      (cond
        (str/includes? url "github.com")    :github
        (str/includes? url "gitlab")        :gitlab
        (str/includes? url "bitbucket.org") :bitbucket
        (not (str/blank? url))              :custom))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2
;; Pack selection and full analysis

(defn select-packs
  "Select policy packs based on detected tech IDs."
  [detected-techs]
  (let [tech-packs (->> @fingerprints
                        (filter #(contains? detected-techs (:tech/id %)))
                        (mapcat #(get % :tech/packs [])))]
    (vec (distinct (concat always-packs tech-packs)))))

(defn analyze-repo
  "Analyze a repository and return its characteristics.
   Returns {:technologies #{tech-ids} :git-host keyword? :packs [string?]}"
  [repo-path]
  (let [by-extension (detect-languages repo-path)
        by-markers   (detect-markers repo-path)
        all-techs    (into by-extension by-markers)
        git-host     (detect-git-host repo-path)
        packs        (select-packs all-techs)]
    {:technologies all-techs
     :git-host     git-host
     :packs        packs}))
