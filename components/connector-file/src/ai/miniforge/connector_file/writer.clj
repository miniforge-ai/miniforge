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

(ns ai.miniforge.connector-file.writer
  "File writing for JSON and EDN formats."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- ^{:stratum 0} posix?
  "True when the filesystem holding `path` supports POSIX permissions."
  [^Path path]
  (contains? (.supportedFileAttributeViews (.getFileSystem path)) "posix"))

(defn- ^{:stratum 0} create-temp-like!
  "Create the empty temp file at `tmp`. When `target` exists and the filesystem
   is POSIX, the temp file gets exactly the target's permissions from creation,
   so the atomic replace neither loosens an owner-only file nor exposes its
   records while they are being written."
  [^Path tmp ^Path target]
  (if (and (posix? tmp) (Files/exists target (into-array java.nio.file.LinkOption [])))
    (let [perms (Files/getPosixFilePermissions target (into-array java.nio.file.LinkOption []))]
      (Files/createFile tmp (into-array FileAttribute [(PosixFilePermissions/asFileAttribute perms)]))
      (Files/setPosixFilePermissions tmp perms))
    (Files/createFile tmp (into-array FileAttribute []))))

(defn- ^{:stratum 0} write-atomically!
  "Write the output of `write-fn` (called with a Writer) to `path` by writing a
   sibling temp file and renaming it over the target, so a crash mid-write never
   leaves a truncated or half-written file where earlier records used to be."
  [path write-fn]
  (let [given  (.getAbsoluteFile (io/file path))
        ;; An existing symlink is followed, so the replace updates the file it
        ;; points at instead of swapping the link itself for a regular file.
        target (if (.exists given)
                 (.toFile (.toRealPath (.toPath given) (into-array java.nio.file.LinkOption [])))
                 given)
        tmp    (io/file (.getParentFile target)
                        (str (.getName target) "." (random-uuid) ".tmp"))]
    (io/make-parents target)
    (try
      (create-temp-like! (.toPath tmp) (.toPath target))
      (with-open [wtr (io/writer tmp)]
        (write-fn wtr))
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists (.toPath tmp))))))

(defn write-json
  "Write records as JSON array to file."
  [path records mode]
  (let [existing (when (and (= mode :append) (.exists (io/file path)))
                   (with-open [rdr (io/reader path)]
                     (json/parse-stream rdr true)))
        all-records (if existing
                      (into (vec existing) records)
                      (vec records))]
    (write-atomically! path #(json/generate-stream all-records %))
    (count records)))

(defn write-edn
  "Write records as EDN vector to file."
  [path records mode]
  (let [existing (when (and (= mode :append) (.exists (io/file path)))
                   (edn/read-string (slurp path)))
        all-records (if existing
                      (into (vec existing) records)
                      (vec records))]
    (write-atomically! path #(.write ^java.io.Writer % ^String (pr-str all-records)))
    (count records)))

(defn write-file
  "Write records to file in the given format. Returns count written."
  [path records format mode]
  (case format
    :json (write-json path records mode)
    :edn  (write-edn path records mode)))
