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
(ns ai.miniforge.qualification.writer
  "Write a report as EDN and JSON under an output directory, one directory
   per gate and one file pair per run."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str])
  (:import [java.nio.file Files LinkOption OpenOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} gate-directory
  "`:pipeline/preflight` → \"pipeline.preflight\". Any character outside
   letters, digits, `_` and `-` in either part becomes `_`, so a gate id
   cannot name a path separator or a `..` component."
  [gate-id]
  (str (str/replace (namespace gate-id) #"[^A-Za-z0-9_-]" "_")
       "."
       (str/replace (name gate-id) #"[^A-Za-z0-9_-]" "_")))

(defn- ^{:stratum 0} run-stem
  "The run's start time and the first 12 characters of its commit, or
   \"no-commit\", with anything outside letters, digits and `-` replaced."
  [{:report/keys [started-at build]}]
  (str (str/replace started-at #"[^0-9A-Za-z-]" "-")
       "-"
       (if-let [sha (not-empty (:build/git-sha build))]
         (str/replace (subs sha 0 (min 12 (count sha))) #"[^0-9A-Za-z]" "-")
         "no-commit")))

(defn- ^{:stratum 0} inside?
  "True when `path` normalizes to a location beneath `dir`."
  [dir path]
  (let [base   (.normalize (.toAbsolutePath (.toPath (io/file dir))))
        target (.normalize (.toAbsolutePath (.toPath (io/file path))))]
    (and (not= base target) (.startsWith target base))))

(defn- ^{:stratum 0} edn-text [report]
  (with-out-str (pprint/pprint report)))

(defn- ^{:stratum 0} json-key
  "A map key as JSON text: a keyword keeps its namespace, `:report/gate-id`
   → \"report/gate-id\"; any other key is its string form."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- ^{:stratum 0} real-inside?
  "True when `path`'s existing parent resolves (symlinks too) beneath `dir`."
  [dir path]
  (let [no-opts (make-array LinkOption 0)
        base    (.toRealPath (.toPath (io/file dir)) no-opts)
        parent  (.toRealPath (.getParent (.toAbsolutePath (.toPath (io/file path)))) no-opts)]
    (.startsWith parent base)))

(defn- ^{:stratum 0} write-new!
  "Create `path` with `text`; fails if anything, a symlink too, is there."
  [path text]
  (Files/write (.toPath (io/file path)) (.getBytes ^String text "UTF-8")
               ^"[Ljava.nio.file.OpenOption;"
               (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} json-key-collision?
  "True when a map in `x` has two keys `json-key` writes alike (`:a/b`, \"a/b\")."
  [x]
  (boolean (some #(and (map? %) (not= (count %) (count (into #{} (map json-key) (keys %)))))
                 (tree-seq coll? seq x))))

(defn- ^{:stratum 1} json-text
  "The report as JSON; keys and keyword values keep their namespaces."
  [report]
  (json/generate-string report {:pretty true :key-fn json-key}))

(defn ^{:stratum 1} report-paths
  "{:edn path :json path} for `report` under `out-dir`."
  [out-dir report]
  (let [dir  (io/file out-dir (gate-directory (:report/gate-id report)))
        stem (run-stem report)]
    {:edn  (str (io/file dir (str stem ".edn")))
     :json (str (io/file dir (str stem ".json")))}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write-report!
  "Write `report` under `out-dir` as EDN and JSON. Returns the two paths.
   Throws, writing neither file, when a path would leave `out-dir` (as
   written or through a symlink), when JSON would merge two keys, or when a
   file already exists; the caller decides what that means for the run."
  [out-dir report]
  (let [{:keys [edn json] :as paths} (report-paths out-dir report)
        refuse (fn [reason] (throw (ex-info reason {:out-dir out-dir :paths paths})))]
    (when-not (and (inside? out-dir edn) (inside? out-dir json))
      (refuse "report path leaves the output directory"))
    (when (json-key-collision? report)
      (refuse "two keys in one map would write the same JSON property"))
    (io/make-parents edn)
    (when-not (and (real-inside? out-dir edn) (real-inside? out-dir json))
      (refuse "report directory resolves outside the output directory"))
    (write-new! edn (edn-text report))
    (write-new! json (json-text report))
    paths))

(comment
  (report-paths "target/qualification"
                {:report/gate-id :pipeline/preflight
                 :report/started-at "2026-11-08T12:00:00Z"
                 :report/build {:build/git-sha nil}}))
