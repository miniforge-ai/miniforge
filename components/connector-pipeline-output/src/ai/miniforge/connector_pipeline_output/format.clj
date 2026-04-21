(ns ai.miniforge.connector-pipeline-output.format
  "Pluggable format transforms for pipeline output.
   Each format writes records and returns the filename used."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string]))

(defmulti write-records
  "Write records to the output directory in the given format.
   Returns the filename of the written records file."
  (fn [format _output-dir _run-id _records] format))

(defmethod write-records :edn
  [_ output-dir run-id records]
  (let [filename "records.edn"
        path (str output-dir "/" run-id "/" filename)]
    (io/make-parents path)
    (spit path (pr-str (vec records)))
    filename))

(defmethod write-records :json
  [_ output-dir run-id records]
  (let [filename "records.json"
        path (str output-dir "/" run-id "/" filename)]
    (io/make-parents path)
    (with-open [wtr (io/writer path)]
      (json/generate-stream records wtr))
    filename))

(defmethod write-records :default
  [format _ _ _]
  (throw (ex-info (str "Unsupported output format: " format)
                  {:format format})))

(defn- sanitize-name
  "Sanitize a dataset name for use in filenames."
  [name]
  (-> (str name)
      (clojure.string/replace #"[^a-zA-Z0-9_-]" "_")
      (clojure.string/lower-case)))

(defn write-dataset-records
  "Write records grouped by dataset name into separate files.
   Returns a vector of {:dataset name :filename str :count int}."
  [format output-dir run-id datasets]
  (mapv (fn [[dataset-name records]]
          (let [safe-name (sanitize-name dataset-name)
                ext       (case format :edn "edn" :json "json" "edn")
                filename  (str "records-" safe-name "." ext)
                path      (str output-dir "/" run-id "/" filename)]
            (io/make-parents path)
            (case format
              :edn  (spit path (pr-str (vec records)))
              :json (with-open [wtr (io/writer path)]
                      (json/generate-stream records wtr)))
            {:dataset  dataset-name
             :filename filename
             :count    (count records)}))
        datasets))

(defn write-manifest
  "Write the manifest EDN file for a pipeline run."
  [output-dir run-id manifest]
  (let [path (str output-dir "/" run-id "/manifest.edn")]
    (io/make-parents path)
    (spit path (pr-str manifest))
    path))

(defn write-json-schema
  "Write the manifest JSON Schema for non-Clojure consumers."
  [output-dir run-id json-schema-map]
  (let [path (str output-dir "/" run-id "/manifest.schema.json")]
    (io/make-parents path)
    (with-open [wtr (io/writer path)]
      (json/generate-stream json-schema-map wtr))
    path))
