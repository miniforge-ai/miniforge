(ns ai.miniforge.pipeline-config.discovery
  "Find pipeline EDN files on disk.
   NOTE: Pipeline files are currently loaded unsigned. A signing/verification
   mechanism should be added before loading untrusted pipeline definitions
   to mitigate injection risk."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [ai.miniforge.schema.interface :as schema]))

(defn- pipeline-file?
  "Return true if the file looks like a pipeline definition."
  [^java.io.File f]
  (and (.isFile f)
       (.endsWith (.getName f) ".edn")))

(defn- read-pipeline-header
  "Parse a pipeline EDN file and return a map of :path, :name, and :version.
   Returns nil when the file cannot be read or parsed, or lacks :pipeline/name."
  [^java.io.File f]
  (try
    (let [content (edn/read-string (slurp f))]
      (when (and (map? content) (:pipeline/name content))
        {:path    (.getAbsolutePath f)
         :name    (:pipeline/name content)
         :version (:pipeline/version content)}))
    (catch Exception _
      nil)))

(defn- pipeline-files-in-dir
  "Return pipeline EDN files found under dir."
  [dir]
  (->> (file-seq dir)
       (filter pipeline-file?)))

(defn discover-pipelines
  "Scan directories for pipeline EDN files.
   Returns schema/success with :pipelines key.
   Files that cannot be read or parsed, or that lack :pipeline/name, are excluded."
  [search-paths]
  (let [dirs    (keep #(let [f (io/file %)] (when (.isDirectory f) f)) search-paths)
        files   (mapcat pipeline-files-in-dir dirs)
        headers (into [] (keep read-pipeline-header) files)]
    (schema/success :pipelines headers)))
