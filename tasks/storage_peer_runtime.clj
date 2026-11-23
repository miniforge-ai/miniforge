;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns storage-peer-runtime
  "Resolve the installed JVM peer classpath for native storage fixture processes."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.regex Pattern]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} separator
  "Split the actual platform classpath, not shell command syntax."
  (re-pattern (Pattern/quote File/pathSeparator)))

(defn- ^{:stratum 0} canonical-entry [directory entry]
  (let [path (if (fs/absolute? entry) entry (fs/path directory entry))]
    (.getCanonicalPath ^File (fs/file path))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} classpath! [workspace]
  (let [directory (str (fs/path workspace "projects/miniforge"))
        reply (process/shell {:dir directory
                              :out :string} "clojure" "-Spath")
        entries (str/split (str/trim (:out reply)) separator)]
    (str/join File/pathSeparator (map (partial canonical-entry directory) entries))))

(comment (canonical-entry "." "."))
