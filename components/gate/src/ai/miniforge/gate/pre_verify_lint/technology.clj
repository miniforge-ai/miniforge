;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint.technology
  "Technology detection for the pre-verify lint gate.

   Maps an artifact's written files to the set of technologies (:clojure,
   :python, :rust, ...) they touch, by file extension. The pre-verify lint
   gate uses this set to select which configured linters to run.

   Layer 0: Extension table and single-file extraction
   Layer 1: File-entry to technology mapping
   Layer 2: Artifact-level technology detection"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Technology detection
(def ^{:stratum 0} ^:private ext->tech
  "File extension to technology mapping."
  {"clj" :clojure "cljs" :clojure "cljc" :clojure
   "py" :python "rs" :rust "go" :go
   "js" :javascript "ts" :typescript
   "swift" :swift "rb" :ruby})

(defn- ^{:stratum 0} file-extension
  "Extract extension from a file path."
  [path]
  (when-let [idx (str/last-index-of (str path) ".")]
    (subs (str path) (inc idx))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} file->tech
  "Map a file entry to its technology keyword, or nil."
  [file-entry]
  (get ext->tech (file-extension (get file-entry :path ""))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} detect-technologies
  "Detect technologies from written file extensions."
  [artifact]
  (into #{} (keep file->tech) (get artifact :code/files [])))
