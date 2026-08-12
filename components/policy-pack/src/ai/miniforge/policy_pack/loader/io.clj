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
(ns ai.miniforge.policy-pack.loader.io
  "Disk I/O for policy packs: rule-file discovery, EDN parsing, pack
   writing, single rule-file loading, and pack discovery within a
   directory. Extracted from `ai.miniforge.policy-pack.loader` (rule 210:
   the combined namespace measured 5 real layers, max 3)."
  (:require
   [ai.miniforge.policy-pack.loader.normalize :as normalize]
   [ai.miniforge.policy-pack.loader.timestamps :as timestamps]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; File discovery and path utilities
(defn ^{:stratum 0} find-rule-files
  "Find all rule .edn files in a rules/ directory, recursive."
  [rules-dir]
  (when (.exists (io/file rules-dir))
    (->> (file-seq (io/file rules-dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".edn")))))

(defn ^{:stratum 0} pack-file?
  "Check if a file is a pack file (pack.edn or *.pack.edn)."
  [file]
  (let [name (.getName file)]
    (or (= name "pack.edn")
        (str/ends-with? name ".pack.edn"))))

;; EDN parsing and validation
(defn ^{:stratum 0} safe-read-edn
  "Safely read EDN from a file.
   Returns {:success? bool :data any :error string}."
  [file]
  (try
    (let [content (slurp file)
          data (edn/read-string content)]
      (schema-validation/success :data data {:error nil}))
    (catch Exception e
      (schema-validation/failure :data (.getMessage e)))))

;; Pack writing
(defn ^{:stratum 0} write-pack-to-file
  "Write a pack manifest to a single EDN file.

   Timestamps are normalized to ISO-8601 strings first: written raw, the
   file cannot be read back at all. An unsupported timestamp is refused —
   `->iso` throws, nothing is written, and the caller gets
   `{:success? false}` instead of a file that fails only on load.

   Arguments:
   - pack - PackManifest
   - file-path - Output file path

   Returns:
   - {:success? bool :error string-or-nil} — :error is nil on success"
  [pack file-path]
  (try
    (let [content (with-out-str
                    (pprint/pprint (timestamps/map-instant-keys pack timestamps/->iso)))]
      (spit file-path content)
      {:success? true :error nil})
    (catch Exception e
      (schema-validation/failure nil (.getMessage e)))))

;------------------------------------------------------------------------------ Layer 1

;; Directory structure loader
(defn ^{:stratum 1} load-rule-file
  "Load a single rule from an EDN file."
  [file]
  (let [{:keys [success? data error]} (safe-read-edn file)]
    (if success?
      (schema-validation/success :rule (normalize/normalize-rule data) {:error nil})
      (schema-validation/failure :rule error))))

(defn ^{:stratum 1} discover-packs
  "Discover all packs in a directory.

   Looks for:
   - *.pack.edn files
   - Subdirectories containing pack.edn

   Arguments:
   - packs-dir - Directory containing packs

   Returns:
   - Vector of {:path string :type :file|:directory}"
  [packs-dir]
  (let [dir (io/file packs-dir)]
    (when (.exists dir)
      (let [;; Find *.pack.edn files
            pack-files (->> (.listFiles dir)
                            (filter #(.isFile %))
                            (filter pack-file?)
                            (map (fn [f]
                                   {:path (.getPath f)
                                    :type :file})))
            ;; Find subdirectories with pack.edn
            pack-dirs (->> (.listFiles dir)
                           (filter #(.isDirectory %))
                           (filter #(.exists (io/file % "pack.edn")))
                           (map (fn [d]
                                  {:path (.getPath d)
                                   :type :directory})))]
        (vec (concat pack-files pack-dirs))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (discover-packs ".miniforge/packs")
  ;; => [{:path ".miniforge/packs/terraform-safety.pack.edn" :type :file}
  ;;     {:path ".miniforge/packs/kubernetes/" :type :directory}]

  :leave-this-here)
