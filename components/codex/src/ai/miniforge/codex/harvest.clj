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
(ns ai.miniforge.codex.harvest
  "HARVEST-*.md logs -> dated harvest decisions (Codex SPEC T1 §8.4).
   An entry is a markdown section; it decides misses only when it carries
   `decided::`, `outcome::` and `misses::`. Read-only."
  (:require [ai.miniforge.codex.messages :as msg]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private field-re
  #"^\s*(?:-\s+)?(decided|outcome|misses)::\s*(.*?)\s*$")

(def ^{:stratum 0} outcomes
  "The two decisions T1 §5.6/§6.4 allow a harvest entry to record."
  {"admitted" :admitted "refused" :refused})

(defn- ^{:stratum 0} harvest-file? [^java.io.File f]
  (and (.isFile f)
       (str/starts-with? (.getName f) "HARVEST-")
       (str/ends-with? (.getName f) ".md")))

(defn- ^{:stratum 0} sections
  "Section texts, each starting at a markdown ATX heading: up to three
   leading spaces, 1-6 `#`, then whitespace. A `#tag` line is not one."
  [text]
  (str/split text #"(?m)^(?= {0,3}#{1,6}(?:[ \t]|$))"))

(defn- ^{:stratum 0} field-pair [line]
  (some-> (re-matches field-re line) rest vec))

(defn- ^{:stratum 0} section-fields [section]
  (into {} (keep field-pair) (str/split-lines section)))

(defn- ^{:stratum 0} parse-date [s]
  (try (java.time.LocalDate/parse s)
       (catch java.time.format.DateTimeParseException _ nil)))

(defn- ^{:stratum 0} parse-miss-ids [s]
  (vec (keep (comp parse-uuid str/trim) (str/split s #","))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fields->decision
  [{:strs [decided outcome misses]}]
  (let [date     (some-> decided parse-date)
        kind     (get outcomes (some-> outcome str/lower-case))
        miss-ids (some-> misses parse-miss-ids)]
    (when (and date kind (seq miss-ids))
      {:decided date :outcome kind :misses miss-ids})))

(defn- ^{:stratum 0} symlink? [^java.io.File f]
  (java.nio.file.Files/isSymbolicLink (.toPath f)))

(defn- ^{:stratum 0} child-kind
  "Symlinked directories are skipped: a link back to an ancestor would
   re-enqueue the tree without end."
  [^java.io.File f]
  (cond
    (symlink? f)      (if (.isDirectory f) :skip :file)
    (.isDirectory f)  :dir
    :else             :file))

(defn- ^{:stratum 1} list-tree
  "Every file under `root`, or {:unlistable dir} for the first directory
   that cannot be listed: file-seq would skip it silently."
  [^java.io.File root]
  (loop [dirs [root] files []]
    (if-let [^java.io.File d (first dirs)]
      (if-let [children (.listFiles d)]
        (let [{subdirs :dir leaves :file} (group-by child-kind children)]
          (recur (into (vec (rest dirs)) subdirs) (into files leaves)))
        {:unlistable (str d)})
      {:files files})))

(defn- ^{:stratum 1} file-decisions [f]
  (keep (comp fields->decision section-fields) (sections (slurp f))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} harvest-decisions
  "Every complete harvest decision under `codex-dir`, as
   {:decisions [{:decided LocalDate :outcome :admitted|:refused
   :misses [uuid ..]}]}, or {:codex/anomaly :codex-unreadable ...} when the
   directory is missing or cannot be read."
  [codex-dir]
  (let [dir (io/file codex-dir)]
    (if-not (.isDirectory dir)
      {:codex/anomaly :codex-unreadable
       :codex/reason (msg/t :anomaly/no-codex-dir {:dir codex-dir})}
      (try
        (let [{:keys [files unlistable]} (list-tree dir)
              logs (filter harvest-file? files)]
          (if unlistable
            {:codex/anomaly :codex-unreadable
             :codex/reason (msg/t :anomaly/unlistable-dir {:dir unlistable})}
            {:decisions (vec (mapcat file-decisions logs))}))
        (catch java.io.IOException e
          {:codex/anomaly :codex-unreadable
           :codex/reason (msg/t :anomaly/harvest-read-failed {:dir codex-dir :error (ex-message e)})})
        (catch SecurityException e
          {:codex/anomaly :codex-unreadable
           :codex/reason (msg/t :anomaly/access-denied {:dir codex-dir :error (ex-message e)})})))))
