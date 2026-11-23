;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.peer-validation
  "Predicates for trusted installed-runtime configuration at the component boundary."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.util.regex Pattern]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} separator
  "Every peer classpath entry must be absolute in the current platform format."
  (re-pattern (Pattern/quote File/pathSeparator)))

(defn ^{:stratum 0} absolute-file? [value]
  (and (instance? File value) (.isAbsolute ^File value)))

(defn- ^{:stratum 0} absolute-entry? [entry]
  (.isAbsolute (File. ^String entry)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} absolute-classpath? [classpath]
  (every? absolute-entry? (str/split classpath separator -1)))

(comment (absolute-classpath? "."))
