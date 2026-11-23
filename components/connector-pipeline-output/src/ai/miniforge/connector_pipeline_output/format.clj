;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.connector-pipeline-output.format
  "Pluggable format transforms for pipeline output.
   Each format writes records and returns the filename used."
  (:require [ai.miniforge.response.interface :as response]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string])
  (:import [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sanitize-name
  "Sanitize a dataset name for use in filenames."
  [name]
  (-> (str name)
      (clojure.string/replace #"[^a-zA-Z0-9_-]" "_")
      (clojure.string/lower-case)))

(defn- ^{:stratum 0} write-atomically!
  "Create an empty sibling temp file, optionally narrow its permissions
   to match the existing target's, call `(write-fn tmp)` to fill it,
   then rename it over `path` atomically. The write therefore occurs
   under the target's restricted mode rather than the process umask,
   so the content is never transiently readable by other users. On
   non-POSIX filesystems the permission step is skipped silently. On
   failure the temp file is removed and the original exception rethrown."
  [path write-fn]
  (let [target (io/file path)
        tmp    (io/file (.getParentFile target)
                        (str "." (random-uuid) ".tmp"))]
    (try
      (.createNewFile tmp)
      (try
        (Files/setPosixFilePermissions
         (.toPath tmp)
         (Files/getPosixFilePermissions
          (.toPath target) (make-array java.nio.file.LinkOption 0)))
        (catch java.nio.file.NoSuchFileException _)
        (catch UnsupportedOperationException _))
      (write-fn tmp)
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch Throwable e
        (try (Files/deleteIfExists (.toPath tmp))
             (catch Throwable c (.addSuppressed e c)))
        (throw e)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} write-edn-atomically!
  "Write the string `content` to `path` atomically."
  [path content]
  (write-atomically! path #(spit % content :encoding "UTF-8")))

(defn- ^{:stratum 1} write-json-atomically!
  "Stream `data` as JSON to `path` atomically."
  [path data]
  (write-atomically! path
                     (fn [tmp]
                       (with-open [wtr (io/writer tmp :encoding "UTF-8")]
                         (json/generate-stream data wtr)))))

;------------------------------------------------------------------------------ Layer 2

(defmulti ^{:stratum 2} write-records
  "Write records to the output directory in the given format.
   Returns the filename of the written records file."
  (fn [format _output-dir _run-id _records] format))

(defmethod ^{:stratum 2} write-records :edn
  [_ output-dir run-id records]
  (let [filename "records.edn"
        path (str output-dir "/" run-id "/" filename)]
    (io/make-parents path)
    (write-edn-atomically! path (pr-str (vec records)))
    filename))

(defmethod ^{:stratum 2} write-records :json
  [_ output-dir run-id records]
  (let [filename "records.json"
        path (str output-dir "/" run-id "/" filename)]
    (io/make-parents path)
    (write-json-atomically! path records)
    filename))

(defmethod ^{:stratum 2} write-records :default
  [format _ _ _]
  (response/throw-anomaly! :anomalies/unsupported
                           (str "Unsupported output format: " format)
                           {:format format}))

(defn ^{:stratum 2} write-manifest
  "Write the manifest EDN file for a pipeline run."
  [output-dir run-id manifest]
  (let [path (str output-dir "/" run-id "/manifest.edn")]
    (io/make-parents path)
    (write-edn-atomically! path (pr-str manifest))
    path))

(defn ^{:stratum 2} write-json-schema
  "Write the manifest JSON Schema for non-Clojure consumers."
  [output-dir run-id json-schema-map]
  (let [path (str output-dir "/" run-id "/manifest.schema.json")]
    (io/make-parents path)
    (write-json-atomically! path json-schema-map)
    path))

(defn ^{:stratum 2} write-dataset-records
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
              :edn  (write-edn-atomically! path (pr-str (vec records)))
              :json (write-json-atomically! path records))
            {:dataset  dataset-name
             :filename filename
             :count    (count records)}))
        datasets))
