;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.discovery-files
  "Filesystem adapters for runtime classification; no namespace loading."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private jvm-only-meta-re
  "Distinctive enough that the only legitimate place it can appear is a
   brick's ns-meta marker."
  #":miniforge/runtime\s+:jvm-only")

(defn ^{:stratum 0} component-interface-file
  [brick-name]
  (io/file "components" brick-name "src" "ai" "miniforge"
           (str/replace brick-name "-" "_") "interface.clj"))

(defn ^{:stratum 0} base-src-files
  [base-name]
  (let [src-dir (io/file "bases" base-name "src")]
    (when (.exists src-dir)
      (->> (file-seq src-dir)
           (filter #(str/ends-with? (.getName %) ".clj"))))))

(defn ^{:stratum 0} path->namespace
  "Convert a .clj file path (relative to src/) to a namespace symbol.
   e.g. ai/miniforge/cli/main.clj -> ai.miniforge.cli.main"
  [src-root clj-file]
  (let [src-path (.getPath src-root)
        file-path (.getPath clj-file)
        relative (subs file-path (inc (count src-path)))]
    (-> relative
        (str/replace #"\.clj$" "")
        (str/replace #"[\\/]" ".")
        (str/replace "_" "-")
        symbol)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} file-marked-jvm-only?
  [^java.io.File f]
  (and (.exists f)
       (boolean (re-find jvm-only-meta-re (slurp f)))))

(comment (component-interface-file "phase-opsv"))
