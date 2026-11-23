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
(ns ai.miniforge.pr-lifecycle.gh-replay-test-support
  "Real gh 2.102 output, captured from miniforge-ai/miniforge PRs, and a
   babashka.process/shell stand-in that replays it. `fake-gh` rejects
   --json fields the way gh does, using gh's own list of available fields."
  (:require
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gh-2-102
  "Captured gh output: `gh pr checks --json` for failing, pending and
   passing PRs, gh's error texts, and `gh run view --log-failed`."
  (-> "ai/miniforge/pr_lifecycle/gh-fixtures/gh_2_102.edn" io/resource slurp edn/read-string))

(defn ^{:stratum 0} gh-ok
  "A shell result for a gh command that exited 0 printing `out`."
  [out]
  {:exit 0 :out out :err ""})

(defn ^{:stratum 0} gh-err
  "A shell result for a gh command that exited `exit` printing `err`."
  [exit err]
  {:exit exit :out "" :err err})

(defn ^{:stratum 0} poll-once
  "Poll a fresh monitor for PR `pr-number` once, with `shell-fn` as gh."
  [shell-fn pr-number]
  (with-redefs [process/shell shell-fn]
    (ci/poll-ci-status
     (ci/create-ci-monitor (random-uuid) (random-uuid) (random-uuid) pr-number "/tmp")
     nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fake-gh
  "babashka.process/shell stand-in answering like gh 2.102. `gh run view`
   returns the captured failed-job log for the captured run id and exit 1
   for any other run. `gh pr checks` exits 1 with gh's error text when a
   --json field is not one gh lists as available, else returns `response`
   ({:exit :out :err})."
  [response]
  (let [stderr (:checks/unknown-field-stderr gh-2-102)
        available (->> (str/split-lines stderr)
                       (drop-while #(not= "Available fields:" %))
                       rest
                       (map str/trim)
                       set)]
    (fn [_opts & args]
      (let [fields (some-> (second (drop-while #(not= "--json" %) args))
                           (str/split #","))]
        (cond
          (not= ["gh" "run" "view"] (take 3 args))
          (if (every? available fields) response (gh-err 1 stderr))

          (= (:run/failed-run-id gh-2-102) (nth args 3))
          (gh-ok (:run/log-failed gh-2-102))

          :else
          (gh-err 1 (str "could not find any workflow run with ID " (nth args 3))))))))
