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
(ns ai.miniforge.pr-lifecycle.monitor-store
  "Where PR monitor state lives on disk, and how it is read and written.

   One directory per GitHub repository:

     <miniforge-home>/state/pr-monitor/repos/<owner>/<name>/budget-<pr>.edn
     <miniforge-home>/state/pr-monitor/repos/<owner>/<name>/watermarks.edn

   A PR number is unique only within its repository, so every file is keyed
   by the repo slug (owner/name) as well as the PR number. GitHub slugs are
   case-insensitive; the directory names are lower-cased.

   Files that older versions wrote directly under state/pr-monitor/
   (budget-<pr>.edn, watermarks.edn) name no repository. They are ignored,
   not migrated: handing one to whichever repository reads it first is the
   cross-repo mix-up this layout exists to prevent.

   Writes go through the config component's file writer: read, merge and
   write under a `<file>.lock` lock, published by atomic rename. A file that
   does not hold one EDN map is never overwritten."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.config.interface.file :as config-file]
   [ai.miniforge.pr-lifecycle.messages :as msg]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io PushbackReader StringReader]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private repo-slug-pattern
  "owner/name, each segment limited to the characters GitHub allows."
  #"([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")

(defn ^{:stratum 0} state-root
  "Base directory for PR monitor state. Read on every call, so a process or
   test that sets MINIFORGE_HOME gets its own state."
  []
  (str (mf-config/miniforge-home) "/state/pr-monitor"))

(defn ^{:stratum 0} no-repo
  "Anomaly for state requested without a usable owner/name repo slug."
  [repo-slug]
  (anomaly/anomaly :invalid-input
                   (msg/system-t :monitor-state/no-repo {:repo (pr-str repo-slug)})
                   {:monitor-state/repo repo-slug}))

(defn ^{:stratum 0} unreadable
  "Anomaly for a state file that exists but does not hold one EDN map."
  [file error]
  (anomaly/anomaly :invalid-input
                   (msg/system-t :monitor-state/unreadable {:path (str file) :error error})
                   {:monitor-state/path (str file)}))

(defn ^{:stratum 0} update-file!
  "Replace `file` with `(f current)` under its lock; `f` gets nil when the
   file is absent or blank. Returns the map now in the file, or an anomaly,
   with nothing written, when the file holds something other than one EDN
   map, the lock stays busy, or the write fails."
  [file f]
  (let [result (config-file/update-config! (str file) f)]
    (if (anomaly/anomaly? result) result (:after result))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} slug-segments
  "[owner name], lower-cased, for an owner/name repo slug, else nil. `.` and
   `..` are rejected so a slug can never name a directory outside the state
   root."
  [repo-slug]
  (when (string? repo-slug)
    (when-let [[_ owner repo-name] (re-matches repo-slug-pattern repo-slug)]
      (when-not (some #{"." ".."} [owner repo-name])
        (mapv str/lower-case [owner repo-name])))))

(defn ^{:stratum 1} read-map
  "nil when `file` does not exist, its map when it holds exactly one EDN map,
   else an anomaly. Never writes. Takes no lock: writers publish by atomic
   rename, so a reader always sees a whole file."
  [file]
  (when (.exists (io/file file))
    (try+
      (let [reader (PushbackReader. (StringReader. (slurp file)))
            eof    (Object.)
            form   (edn/read {:eof eof} reader)
            extra  (edn/read {:eof eof} reader)]
        (cond
          (identical? eof form)        (unreadable file "empty file")
          (not (identical? eof extra)) (unreadable file "more than one top-level form")
          (map? form)                  form
          :else                        (unreadable file (str "top-level form is " (pr-str (type form))))))
      (catch Exception e
        (unreadable file (or (ex-message e) (str (type e))))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} repo-file
  "State file `file-name` for `repo-slug`, or nil when the slug is not owner/name."
  [repo-slug file-name]
  (when-let [[owner repo-name] (slug-segments repo-slug)]
    (io/file (state-root) "repos" owner repo-name file-name)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (repo-file "miniforge-ai/miniforge" "watermarks.edn")
  ;; => #object[java.io.File ".../state/pr-monitor/repos/miniforge-ai/miniforge/watermarks.edn"]
  :leave-this-here)
