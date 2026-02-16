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

(ns ai.miniforge.pr-sync.status
  "Pure PR/MR status mapping — Domain stratum.

   Converts provider-specific PR data (GitHub, GitLab) into normalized
   TrainPR maps with canonical status keywords. Zero I/O.

   Layer 0: Status constants and classification sets
   Layer 1: GitHub PR status mapping
   Layer 2: GitLab MR status mapping + unified conversion"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Status constants

(def ^:private failed-check-conclusions
  #{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED" "STARTUP_FAILURE"})

(def ^:private passing-check-conclusions
  #{"SUCCESS" "NEUTRAL" "SKIPPED"})

(def ^:private pending-check-states
  #{"PENDING" "QUEUED" "IN_PROGRESS" "WAITING" "REQUESTED"})

;------------------------------------------------------------------------------ Layer 1
;; GitHub PR status mapping (pure)

(defn pr-status-from-provider
  "Map GitHub PR provider data to normalized PR status keyword.
   Input: map with :state, :isDraft, :reviewDecision keys."
  [pr]
  (let [state (some-> (:state pr) str str/upper-case)
        draft? (boolean (:isDraft pr))
        decision (some-> (:reviewDecision pr) str str/upper-case)]
    (cond
      (and state (not= "OPEN" state)) :closed
      draft? :draft
      (= "APPROVED" decision) :approved
      (= "CHANGES_REQUESTED" decision) :changes-requested
      (= "REVIEW_REQUIRED" decision) :reviewing
      :else :open)))

(defn check-rollup->ci-status
  "Map GitHub statusCheckRollup to normalized CI status keyword."
  [rollup]
  (let [entries (cond
                  (nil? rollup) []
                  (sequential? rollup) rollup
                  :else [rollup])
        conclusions (keep #(some-> (:conclusion %) str str/upper-case) entries)
        statuses (keep #(some-> (:status %) str str/upper-case) entries)]
    (cond
      (some failed-check-conclusions conclusions) :failed
      (some pending-check-states statuses) :running
      (and (seq conclusions)
           (every? passing-check-conclusions conclusions)) :passed
      (seq entries) :pending
      :else :pending)))

(defn provider-pr->train-pr
  "Convert a GitHub provider PR map to a normalized TrainPR map.
   Adds :pr/repo when repo is provided."
  ([pr] (provider-pr->train-pr pr nil))
  ([pr repo]
   (cond-> {:pr/number    (:number pr)
            :pr/title     (:title pr)
            :pr/url       (:url pr)
            :pr/branch    (:headRefName pr)
            :pr/status    (pr-status-from-provider pr)
            :pr/ci-status (check-rollup->ci-status (:statusCheckRollup pr))}
     repo (assoc :pr/repo repo))))

;------------------------------------------------------------------------------ Layer 2
;; GitLab MR status mapping + unified conversion

(defn- gitlab-status
  [mr]
  (let [state (some-> (:state mr) str str/lower-case)
        draft? (or (true? (:draft mr))
                   (true? (:work_in_progress mr))
                   (str/starts-with? (str/lower-case (or (:title mr) "")) "draft:"))
        merge-status (some-> (:merge_status mr) str str/lower-case)
        conflicts? (true? (:has_conflicts mr))]
    (cond
      (not= "opened" state) :closed
      draft? :draft
      conflicts? :changes-requested
      (contains? #{"can_be_merged" "mergeable"} merge-status) :merge-ready
      :else :open)))

(defn- gitlab-ci-status
  [mr]
  (let [status (some-> (or (get-in mr [:head_pipeline :status])
                           (get-in mr [:pipeline :status]))
                       str
                       str/lower-case)]
    (case status
      ("success" "passed") :passed
      ("failed" "canceled" "cancelled" "skipped") :failed
      ("running" "pending" "created" "preparing" "waiting_for_resource") :running
      :pending)))

(defn gitlab-mr->train-pr
  "Convert a GitLab MR map to a normalized TrainPR map."
  [mr repo]
  {:pr/number    (:iid mr)
   :pr/title     (:title mr)
   :pr/url       (:web_url mr)
   :pr/branch    (:source_branch mr)
   :pr/status    (gitlab-status mr)
   :pr/ci-status (gitlab-ci-status mr)
   :pr/repo      repo})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Status mapping
  (pr-status-from-provider {:state "OPEN" :isDraft false :reviewDecision "APPROVED"})
  ;; => :approved

  (check-rollup->ci-status [{:conclusion "SUCCESS"} {:conclusion "SUCCESS"}])
  ;; => :passed

  (gitlab-mr->train-pr {:iid 1 :title "Test" :state "opened" :source_branch "fix"
                         :web_url "https://gitlab.com/g/p/-/merge_requests/1"
                         :draft false :merge_status "can_be_merged"
                         :head_pipeline {:status "success"}}
                        "gitlab:g/p")
  ;; => {:pr/number 1, :pr/title "Test", ...}

  :leave-this-here)
