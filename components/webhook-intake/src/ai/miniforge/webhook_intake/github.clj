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
(ns ai.miniforge.webhook-intake.github
  "GitHub webhook deliveries as event-stream events. Workflow triggers
   match events on :event/type, :pr/repo and :pr/branch, so every event
   carries the repository's full name and the head branch.

   Mapped deliveries:
   - pull_request closed with merged = true → :pr/merged
   - pull_request opened / reopened         → :pr/opened
   - check_suite completed failure / timed_out → :pr/ci-failed, one per
     same-repository pull request the suite lists, with the suite's app and
     id (GitHub sends one suite per app and per Actions workflow run, so a
     passing suite says nothing about the PR's other checks)

   A pull request from a fork is not mapped: its branch and code come from
   outside the repository, and a trigger would run a workflow on them. A
   check suite listing no pull request is not mapped either: fork suites
   carry the fork's own branch name and list none.
   Every other delivery (ping included) maps to no events."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} opened-actions
  "pull_request actions that announce an open pull request."
  #{"opened" "reopened"})

(def ^{:stratum 0} failed-conclusions
  "check_suite conclusions that mean the suite's checks failed."
  #{"failure" "timed_out"})

(defn ^{:stratum 0} delivery-event
  "The event-stream event for one GitHub delivery: the common envelope
   from the delivery's headers and payload, plus `fields`."
  [event-type {:keys [event-name delivery-id payload]} fields]
  (merge {:event/type      event-type
          :event/id        (random-uuid)
          :event/timestamp (java.util.Date.)
          :github/event    event-name
          :github/delivery delivery-id
          :github/action   (:action payload)
          :github/sender   (get-in payload [:sender :login])
          :pr/repo         (get-in payload [:repository :full_name])}
         fields))

(defn ^{:stratum 0} same-repo-pull-request?
  "True for a check suite's pull-request entry whose head and base
   repositories are the same (by id)."
  [{:keys [head base]}]
  (let [head-repo (get-in head [:repo :id])]
    (and (some? head-repo) (= head-repo (get-in base [:repo :id])))))

(defn ^{:stratum 0} fork-pull-request?
  "True when the pull request's head repository is not its base
   repository, or is unknown (a deleted fork)."
  [pull-request]
  (let [head-repo (get-in pull-request [:head :repo :full_name])]
    (or (str/blank? head-repo)
        (not= head-repo (get-in pull-request [:base :repo :full_name])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pull-request-events
  "Events for a pull_request delivery."
  [{:keys [payload] :as delivery}]
  (let [{:keys [action pull_request]} payload
        fields {:pr/id     (:number pull_request)
                :pr/url    (:html_url pull_request)
                :pr/branch (get-in pull_request [:head :ref])
                :pr/sha    (get-in pull_request [:head :sha])
                :pr/base-branch (get-in pull_request [:base :ref])}]
    (cond
      (fork-pull-request? pull_request)
      []

      (and (= "closed" action) (true? (:merged pull_request)))
      [(delivery-event :pr/merged delivery
                       (assoc fields :pr/merge-sha (:merge_commit_sha pull_request)))]

      (contains? opened-actions action)
      [(delivery-event :pr/opened delivery fields)]

      :else
      [])))

(defn ^{:stratum 1} check-suite-events
  "Events for a check_suite delivery."
  [{:keys [payload] :as delivery}]
  (let [{:keys [action check_suite]} payload
        fields {:pr/branch        (:head_branch check_suite)
                :pr/sha           (:head_sha check_suite)
                :ci/conclusion    (:conclusion check_suite)
                :ci/app           (get-in check_suite [:app :slug])
                :ci/check-suite-id (:id check_suite)}]
    (if (and (= "completed" action) (contains? failed-conclusions (:conclusion check_suite)))
      (mapv #(delivery-event :pr/ci-failed delivery (assoc fields :pr/id (:number %)))
            (filter same-repo-pull-request? (:pull_requests check_suite)))
      [])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} delivery->events
  "Event-stream events for a GitHub delivery
   {:event-name X-GitHub-Event, :delivery-id X-GitHub-Delivery,
    :payload parsed JSON body with keyword keys}. Empty for deliveries
   miniforge does not act on."
  [delivery]
  (case (:event-name delivery)
    "pull_request" (pull-request-events delivery)
    "check_suite"  (check-suite-events delivery)
    []))
