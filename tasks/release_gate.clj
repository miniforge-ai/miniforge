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
(ns release-gate
  "CI check for `bb release` (release/cut!): the newest ci.yml push run
   on main for the commit about to be tagged must have concluded success.
   `bb release` itself runs no tests, so this is what backs the
   \"Stable\" tag it writes. release.yml's `gate` job applies the same
   rule to every tag push and manual dispatch."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ci-runs-jq
  "jq filter printing one `<id> <status> <conclusion>` line per workflow run."
  ".workflow_runs[] | \"\\(.id) \\(.status) \\(.conclusion)\"")

(defn ^{:stratum 0} ci-runs-path
  "gh api path listing ci.yml push runs on main for `sha`."
  [sha]
  (str "repos/{owner}/{repo}/actions/workflows/ci.yml/runs"
       "?head_sha=" sha "&event=push&branch=main&per_page=100"))

(defn ^{:stratum 0} parse-run-lines
  "Parse `<id> <status> <conclusion>` lines (the ci-runs-jq output) into
   [{:id n :status s :conclusion c}]. A jq null conclusion reads as nil."
  [out]
  (vec (for [line (str/split-lines (str out))
             :let [[id status conclusion] (str/split (str/trim line) #"\s+")]
             :when (and (not (str/blank? id)) status)]
         {:id (parse-long id)
          :status status
          :conclusion (when-not (= "null" conclusion) conclusion)})))

(defn ^{:stratum 0} ci-verdict
  "Classify one commit's ci.yml runs by the newest (highest :id), so a
   later failing run outweighs an earlier pass (a re-run of the same run
   updates that run in place): :missing with no runs, :pending while the
   newest has not finished, :green when it concluded success, else :red."
  [runs]
  (let [newest (last (sort-by :id runs))]
    (cond
      (nil? newest) :missing
      (not= "completed" (:status newest)) :pending
      (= "success" (:conclusion newest)) :green
      :else :red)))

(defn ^{:stratum 0} verdict-blocker
  "nil for a :green verdict, else the reason `sha` must not be released."
  [sha verdict]
  (case verdict
    :green nil
    :pending (str "CI (ci.yml) is still running for " sha "; release once it passes.")
    :red (str "CI (ci.yml) did not pass for " sha "; fix main before releasing.")
    :missing (str "No ci.yml push run on main for " sha
                  "; push this commit to main and let CI pass first.")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ci-runs
  "{:runs [...]} for `sha` from the GitHub API via `gh`, or {:error msg}
   when gh is missing or the call fails."
  [sha]
  (if-not (fs/which "gh")
    {:error "gh is not on PATH"}
    (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                       "gh" "api" (ci-runs-path sha)
                                       "--jq" ci-runs-jq)]
      (if (zero? exit)
        {:runs (parse-run-lines out)}
        {:error (str/trim (str err))}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ci-blocker
  "nil when `sha`'s CI passed, else a message saying why it cannot be
   released. Fails closed: a failed lookup blocks."
  [sha]
  (let [{:keys [runs error]} (ci-runs sha)]
    (if error
      (str "Could not check CI for " sha " (" error "); not releasing.")
      (verdict-blocker sha (ci-verdict runs)))))
