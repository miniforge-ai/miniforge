(ns ai.miniforge.data-foundry.pipeline-config.discovery
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
  "Read just the name and version from a pipeline EDN file, without full parsing."
  [^java.io.File f]
  (try
    (let [content (edn/read-string (slurp f))]
      {:path    (.getAbsolutePath f)
       :name    (:pipeline/name content)
       :version (:pipeline/version content)})
    (catch Exception _
      {:path (.getAbsolutePath f) :name nil :version nil})))

(defn discover-pipelines
  "Scan directories for pipeline EDN files.
   Returns schema/success with :pipelines key."
  [search-paths]
  (let [dirs   (keep #(let [f (io/file %)] (when (.isDirectory f) f)) search-paths)
        files  (mapcat (fn [dir]
                         (->> (file-seq dir)
                              (filter pipeline-file?)))
                       dirs)
        headers (mapv read-pipeline-header files)]
    (schema/success :pipelines headers)))
