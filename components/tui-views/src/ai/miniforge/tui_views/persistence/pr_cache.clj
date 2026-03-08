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

(ns ai.miniforge.tui-views.persistence.pr-cache
  "Disk-backed cache for PR analysis results (risk triage, policy evaluation).

   Avoids re-running expensive LLM-based risk triage and policy gate checks
   on every TUI session. Results are cached per-PR, keyed by [repo, pr-number],
   and invalidated when the PR's fingerprint (additions, deletions, status,
   ci-status, head-sha) changes.

   Cache file: ~/.miniforge/cache/pr-analysis.edn

   Layer 0: Fingerprint + cache entry helpers
   Layer 1: Read/write cache file
   Layer 2: Apply cache to model, persist from model"
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Fingerprint + cache entry helpers

(defn pr-fingerprint
  "Compute a fingerprint for a PR that determines cache validity.
   When any of these fields change, cached analysis is stale."
  [pr]
  {:additions  (get pr :pr/additions 0)
   :deletions  (get pr :pr/deletions 0)
   :status     (get pr :pr/status)
   :ci-status  (get pr :pr/ci-status)
   :head-sha   (get pr :pr/head-sha)})

(defn pr-cache-key
  "Canonical cache key for a PR: [repo-string, pr-number]."
  [pr]
  [(:pr/repo pr) (:pr/number pr)])

(defn cache-entry
  "Build a cache entry for a PR with its current analysis results."
  [pr & [{:keys [agent-risk]}]]
  (let [fp (pr-fingerprint pr)]
    (cond-> {:fingerprint fp
             :cached-at   (System/currentTimeMillis)}
      (:pr/policy pr)
      (assoc :policy (:pr/policy pr))
      agent-risk
      (assoc :agent-risk agent-risk))))

(defn entry-valid?
  "True when a cache entry's fingerprint matches the current PR."
  [entry pr]
  (= (:fingerprint entry) (pr-fingerprint pr)))

;------------------------------------------------------------------------------ Layer 1
;; Read/write cache file

(def ^:private cache-dir-path
  "Default cache directory."
  (delay (io/file (System/getProperty "user.home") ".miniforge" "cache")))

(defn cache-file
  "Returns the cache file path."
  [& [{:keys [dir]}]]
  (io/file (or dir @cache-dir-path) "pr-analysis.edn"))

(defn read-cache
  "Read the PR analysis cache from disk. Returns map of {[repo num] -> entry}."
  [& [opts]]
  (let [f (cache-file opts)]
    (if (.exists f)
      (try
        (let [data (edn/read-string (slurp f))]
          (if (map? data) data {}))
        (catch Exception _ {}))
      {})))

(defn write-cache!
  "Write the PR analysis cache to disk. Runs in a future to avoid blocking."
  [entries & [opts]]
  (future
    (try
      (let [f (cache-file opts)]
        (.mkdirs (.getParentFile f))
        (spit f (pr-str entries)))
      (catch Exception _ nil)))
  nil)

;------------------------------------------------------------------------------ Layer 2
;; Apply cache to model, persist from model

(defn apply-cached-policy
  "Apply cached policy results to PRs that haven't been evaluated yet.
   Only applies when the cache entry's fingerprint matches the current PR."
  [prs cache]
  (mapv (fn [pr]
          (if (:pr/policy pr)
            pr ;; already evaluated, don't override
            (let [k (pr-cache-key pr)
                  entry (get cache k)]
              (if (and entry (entry-valid? entry pr) (:policy entry))
                (assoc pr :pr/policy (:policy entry))
                pr))))
        prs))

(defn apply-cached-agent-risk
  "Extract cached agent-risk entries into a {[repo num] -> risk-map} map.
   Only includes entries whose fingerprint matches a current PR."
  [prs cache]
  (reduce (fn [acc pr]
            (let [k (pr-cache-key pr)
                  entry (get cache k)]
              (if (and entry (entry-valid? entry pr) (:agent-risk entry))
                (assoc acc k (:agent-risk entry))
                acc)))
          {}
          prs))

(defn persist-analysis!
  "Persist current PR analysis results to the cache file.
   Merges with existing cache to preserve entries for PRs not currently loaded."
  [prs agent-risk & [opts]]
  (let [existing (read-cache opts)
        updated (reduce (fn [cache pr]
                          (let [k (pr-cache-key pr)
                                risk (get agent-risk k)]
                            (if (or (:pr/policy pr) risk)
                              (assoc cache k (cache-entry pr {:agent-risk risk}))
                              cache)))
                        existing
                        prs)]
    (write-cache! updated opts)))

(defn persist-policy-result!
  "Persist a single PR's policy evaluation result to the cache."
  [pr-id policy-result prs & [opts]]
  (let [[repo number] pr-id
        pr (some #(when (and (= (:pr/repo %) repo)
                             (= (:pr/number %) number))
                    %)
                 prs)]
    (when pr
      (let [existing (read-cache opts)
            k pr-id
            entry (-> (or (get existing k) {})
                      (assoc :fingerprint (pr-fingerprint pr)
                             :cached-at (System/currentTimeMillis)
                             :policy policy-result))]
        (write-cache! (assoc existing k entry) opts)))))

(defn persist-risk-triage!
  "Persist fleet risk triage results to the cache."
  [assessments prs & [opts]]
  (let [existing (read-cache opts)
        pr-by-key (into {} (map (fn [pr] [(pr-cache-key pr) pr]) prs))
        updated (reduce (fn [cache [k risk]]
                          (if-let [pr (get pr-by-key k)]
                            (let [entry (-> (or (get cache k) {})
                                           (assoc :fingerprint (pr-fingerprint pr)
                                                  :cached-at (System/currentTimeMillis)
                                                  :agent-risk risk))]
                              (assoc cache k entry))
                            cache))
                        existing
                        assessments)]
    (write-cache! updated opts)))
