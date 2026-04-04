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

(ns ai.miniforge.cli.web.fleet
  "Fleet status and aggregation."
  (:require [ai.miniforge.cli.web.github :as github]))

(defn format-time-ago [iso-timestamp]
  (try
    (let [then (java.time.Instant/parse iso-timestamp)
          now (java.time.Instant/now)
          seconds (.getSeconds (java.time.Duration/between then now))
          minutes (quot seconds 60)
          hours (quot minutes 60)
          days (quot hours 24)]
      (cond
        (< seconds 60) (str seconds "s ago")
        (< minutes 60) (str minutes "m ago")
        (< hours 24) (str hours "h ago")
        :else (str days "d ago")))
    (catch Exception _ "unknown")))

(defn get-workflow-status [repos]
  (let [all-runs (->> repos
                      (mapcat #(map (fn [run] (assoc run :repo %))
                                    (github/fetch-workflow-runs %))))
        running (filter #(= "in_progress" (:status %)) all-runs)
        completed (filter #(= "completed" (:status %)) all-runs)
        failed (filter #(and (= "completed" (:status %))
                             (#{"failure" "timed_out" "startup_failure"} (:conclusion %))) completed)
        succeeded (filter #(and (= "completed" (:status %))
                                (= "success" (:conclusion %))) completed)]
    {:total (count all-runs)
     :running (count running)
     :failed (count failed)
     :succeeded (count succeeded)
     :runs (->> all-runs (sort-by :createdAt) reverse (take 5))}))

(defn get-status [repos]
  (let [gh-status (github/check-auth)
        repo-statuses (when (:available gh-status)
                        (map github/check-repo repos))
        accessible-count (count (filter :accessible repo-statuses))
        total-repos (count repos)]
    {:gh-cli gh-status
     :repos {:total total-repos
             :accessible accessible-count
             :statuses repo-statuses}
     :overall (cond
                (not (:available gh-status)) :error
                (zero? total-repos) :warning
                (< accessible-count total-repos) :degraded
                :else :healthy)
     :last-check (java.time.Instant/now)}))

(defn generate-summary [repos-with-prs]
  (let [all-prs (mapcat :prs repos-with-prs)
        high-risk (filter #(= :high (get-in % [:analysis :risk])) all-prs)
        medium-risk (filter #(= :medium (get-in % [:analysis :risk])) all-prs)
        low-risk (filter #(= :low (get-in % [:analysis :risk])) all-prs)]
    {:total (count all-prs)
     :high-risk {:count (count high-risk) :prs (take 3 high-risk)}
     :medium-risk {:count (count medium-risk) :prs (take 3 medium-risk)}
     :low-risk {:count (count low-risk) :prs low-risk}
     :recommendation
     (cond
       (pos? (count high-risk))
       (str "⚠️ " (count high-risk) " high-risk PR(s) need careful review before any approvals")

       (> (count medium-risk) 5)
       (str "📋 " (count medium-risk) " PRs need review - consider batch reviewing similar ones")

       (and (pos? (count low-risk)) (zero? (count medium-risk)) (zero? (count high-risk)))
       (str "✅ All " (count low-risk) " PR(s) are low-risk - safe to batch approve")

       (zero? (count all-prs))
       "🎉 No open PRs - fleet is clean!"

       :else
       (str "Mixed risk levels - review " (count high-risk) " high, " (count medium-risk) " medium risk PRs"))}))
