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

(ns ai.miniforge.tui-views.persistence.github
  "GitHub CLI wrappers for fetching PR diffs and details.

   Uses `gh pr diff` and `gh pr view` via babashka.process/shell.
   All functions return nil on failure (non-zero exit, exception, parse error)."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0
;; Validation and CLI helpers

(def ^:private shell-opts
  {:out :string :err :string})

(def ^:private detail-fields
  "title,body,labels,files")

(defn- assert-request! [repo number]
  (assert (string? repo) "repo must be a string")
  (assert (some? number) "number must be present"))

(defn- assert-composite-request! [repo number]
  (assert-request! repo number)
  (assert (or (integer? number) (string? number))
          "number must be an integer or string"))

(defn- coerce-number [number]
  (long (if (string? number) (Long/parseLong number) number)))

(defn- run-gh [& args]
  (apply process/shell shell-opts args))

(defn- successful-output [result]
  (when (zero? (:exit result))
    (or (get result :out) "")))

;------------------------------------------------------------------------------ Layer 1
;; Individual fetchers

(defn fetch-pr-diff
  "Fetch the diff for a PR via `gh pr diff`.

   Returns the trimmed diff string on success, nil on failure."
  [repo number]
  (assert-request! repo number)
  (try
    (some-> (run-gh "gh" "pr" "diff" (str number) "--repo" repo)
            successful-output
            str/trim)
    (catch Exception _
      nil)))

(defn fetch-pr-detail
  "Fetch PR detail (title, body, labels, files) via `gh pr view --json`.

   Returns a parsed map with keyword keys on success, nil on failure."
  [repo number]
  (assert-request! repo number)
  (try
    (some-> (run-gh "gh" "pr" "view" (str number) "--repo" repo
                    "--json" detail-fields)
            successful-output
            (json/parse-string true))
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Layer 2
;; Composite fetcher

(defn fetch-pr-diff-and-detail
  "Fetch both diff and detail for a PR. Coerces number to long.

   Returns {:diff string-or-nil, :detail map-or-nil, :repo string, :number long}."
  [repo number]
  (assert-composite-request! repo number)
  (let [n (coerce-number number)
        diff-future (future (try (fetch-pr-diff repo n) (catch Throwable _ nil)))
        detail-future (future (try (fetch-pr-detail repo n) (catch Throwable _ nil)))]
    {:diff   @diff-future
     :detail @detail-future
     :repo   repo
     :number n}))
