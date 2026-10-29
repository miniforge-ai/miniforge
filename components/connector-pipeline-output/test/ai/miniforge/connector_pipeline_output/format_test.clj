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
(ns ai.miniforge.connector-pipeline-output.format-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-pipeline-output.format :as format]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "pipeline-output-format-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} leftover-temp-files
  [^java.io.File dir]
  (->> (file-seq dir)
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".tmp"))))

(defn- ^{:stratum 0} posix-mode
  [^java.io.File f]
  (java.nio.file.Files/getPosixFilePermissions
   (.toPath f) (make-array java.nio.file.LinkOption 0)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} writes-land-with-default-mode-and-no-temp-files-test
  (testing "manifest and JSON schema are complete, carry the umask mode, and leave no temp file"
    (let [dir (temp-dir)
          manifest-path (format/write-manifest (str dir) "run-1" {:run/id "run-1"})
          schema-path (format/write-json-schema (str dir) "run-1" {"type" "object"})
          ;; A file spit in the same place shows what mode the umask gives.
          reference (io/file dir "run-1" "reference")]
      (spit reference "x")
      (is (= {:run/id "run-1"} (edn/read-string (slurp manifest-path))))
      (is (= {"type" "object"} (json/parse-string (slurp schema-path))))
      (is (empty? (leftover-temp-files dir)))
      (when (.supportsFileAttributeView
             (java.nio.file.Files/getFileStore (.toPath reference)) "posix")
        (is (= (posix-mode reference) (posix-mode (io/file manifest-path)))
            "the output keeps the mode a direct write would give it")
        (is (= (posix-mode reference) (posix-mode (io/file schema-path))))))))

(deftest ^{:stratum 1} failed-write-leaves-no-temp-file-test
  (testing "when the replacing move fails, the error surfaces and no temp file remains"
    (let [dir (temp-dir)
          blocker (io/file dir "run-1" "manifest.edn")]
      ;; A non-empty directory where the manifest belongs makes the move fail.
      (.mkdirs blocker)
      (spit (io/file blocker "keep") "x")
      (is (thrown? java.io.IOException
                   (format/write-manifest (str dir) "run-1" {:run/id "run-1"})))
      (is (empty? (leftover-temp-files dir))))))

(deftest ^{:stratum 1} overwrite-preserves-restricted-permissions-test
  (testing "overwriting an existing output does not widen its restricted mode"
    (let [dir  (temp-dir)
          path (format/write-manifest (str dir) "run-1" {:version 1})
          f    (io/file path)]
      (when (.supportsFileAttributeView
             (java.nio.file.Files/getFileStore (.toPath f)) "posix")
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath f)
         (java.util.EnumSet/of
          java.nio.file.attribute.PosixFilePermission/OWNER_READ
          java.nio.file.attribute.PosixFilePermission/OWNER_WRITE))
        (format/write-manifest (str dir) "run-1" {:version 2})
        (is (= (java.util.EnumSet/of
                java.nio.file.attribute.PosixFilePermission/OWNER_READ
                java.nio.file.attribute.PosixFilePermission/OWNER_WRITE)
               (java.nio.file.Files/getPosixFilePermissions
                (.toPath f) (make-array java.nio.file.LinkOption 0)))
            "restricted mode is not widened by an overwrite")))))

(deftest ^{:stratum 1} existing-output-survives-failed-overwrite-test
  (testing "when the write fails the original output is left untouched"
    (let [dir     (temp-dir)
          out-dir (io/file dir "run-1")
          path    (format/write-manifest (str dir) "run-1" {:version 1})
          original (slurp path)]
      (when (.supportsFileAttributeView
             (java.nio.file.Files/getFileStore (.toPath out-dir)) "posix")
        ;; Remove write permission so temp-file creation inside out-dir fails.
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath out-dir)
         (java.util.EnumSet/of
          java.nio.file.attribute.PosixFilePermission/OWNER_READ
          java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE))
        (try
          ;; Root writes into a read-only directory anyway; nothing to assert there.
          (when-not (.canWrite out-dir)
            (is (thrown? java.io.IOException
                         (format/write-manifest (str dir) "run-1" {:version 2})))
            (is (= original (slurp path)) "original bytes intact after failed overwrite")
            (is (empty? (leftover-temp-files out-dir)) "no temp file survives"))
          (finally
            (java.nio.file.Files/setPosixFilePermissions
             (.toPath out-dir)
             (java.util.EnumSet/of
              java.nio.file.attribute.PosixFilePermission/OWNER_READ
              java.nio.file.attribute.PosixFilePermission/OWNER_WRITE
              java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE))))))))
