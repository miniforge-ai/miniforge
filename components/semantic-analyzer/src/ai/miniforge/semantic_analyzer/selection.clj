;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.selection
  "What the analyzer judges: which rules need an LLM at all, and which
   files are in a rule's path scope and small enough to send.

   Layer 0: behavioral-rule filtering, glob matching, path relativization,
     size predicates
   Layer 1: the per-file judgeability predicates (over Layer 0)
   Layer 2: repo file selection for one rule (over Layer 1)"
  (:require
   [ai.miniforge.semantic-analyzer.limits :as limits]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} behavioral-rules
  "Filter rules to those with :rule/knowledge-content (behavioral/semantic rules).
   These are the rules that need LLM analysis, not mechanical scanning."
  [pack-rules]
  (filterv :rule/knowledge-content pack-rules))

;; NOTE: This mirrors compliance-scanner.scan/globs->file-pred.
;; Glob matching should be extracted to repo-index component.
(defn- ^{:stratum 0} glob-matcher
  "Create a PathMatcher for a glob pattern."
  [glob]
  (.getPathMatcher (java.nio.file.FileSystems/getDefault) (str "glob:" glob)))

(defn ^{:stratum 0} relativize
  "Get the relative path of a file from a root directory."
  [repo-path f]
  (.toString (.relativize (.toPath (io/file repo-path)) (.toPath f))))

(defn- ^{:stratum 0} under-size-limit?
  "True when a file is under the max size limit."
  [f]
  (< (.length f) limits/max-file-size-bytes))

(defn ^{:stratum 0} file-under-size-limit?
  "True when an in-memory file's content is under the byte size limit. Uses `<`
   to match the repo-scan path's `under-size-limit?` (a file exactly at the
   limit is excluded by both)."
  [content]
  (< (count (.getBytes ^String content "UTF-8")) limits/max-file-size-bytes))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} file-matches-globs?
  "Check if a file path matches any of the rule's file globs."
  [file-path globs]
  (let [path (java.nio.file.Paths/get file-path (into-array String []))]
    (some #(.matches (glob-matcher %) path) globs)))

(defn ^{:stratum 1} judgeable-file?
  "True when a changed-file entry has the string path/content shape the judge
   needs and is under the size limit. (A non-string path would throw in the glob
   match, so this guards it.)"
  [{:keys [path content]}]
  (and (string? path) (string? content) (file-under-size-limit? content)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} select-files-for-rule
  "Select files from repo that match a rule's file globs.
   Limits to max-files-per-rule, skips files over max-file-size-bytes."
  [repo-path rule]
  (let [globs (get-in rule [:rule/applies-to :file-globs] ["**/*"])]
    (->> (file-seq (io/file repo-path))
         (filter #(.isFile %))
         (filter under-size-limit?)
         (filter #(file-matches-globs? (relativize repo-path %) globs))
         (take limits/max-files-per-rule))))
