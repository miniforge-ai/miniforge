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

(def failed-check-conclusions
  #{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED" "STARTUP_FAILURE"})

(def passing-check-conclusions
  #{"SUCCESS" "NEUTRAL" "SKIPPED"})

(def pending-check-states
  #{"PENDING" "QUEUED" "IN_PROGRESS" "WAITING" "REQUESTED"})

;------------------------------------------------------------------------------ Layer 1
;; GitHub PR status mapping (pure)

(defn pr-status-from-provider
  "Map GitHub PR provider data to normalized PR status keyword.
   Input: map with :state, :isDraft, :reviewDecision keys."
  [pr]
  (let [state (some-> (:state pr) str str/upper-case)
        draft? (boolean (:isDraft pr))
        merged? (boolean (or (:mergedAt pr) (= "MERGED" state)))
        decision (some-> (:reviewDecision pr) str str/upper-case)]
    (cond
      merged? :merged
      (and state (not= "OPEN" state)) :closed
      draft? :draft
      (= "APPROVED" decision) :approved
      (= "CHANGES_REQUESTED" decision) :changes-requested
      (= "REVIEW_REQUIRED" decision) :reviewing
      :else :open)))

(defn check-rollup->ci-checks
  "Extract individual check results from GitHub statusCheckRollup.
   Returns vector of {:name str :conclusion kw :status kw}."
  [rollup]
  (let [entries (cond
                  (nil? rollup) []
                  (sequential? rollup) rollup
                  :else [rollup])]
    (mapv (fn [entry]
            {:name       (or (:name entry) (:context entry) "unknown")
             :conclusion (some-> (:conclusion entry) str str/lower-case keyword)
             :status     (some-> (:status entry) str str/lower-case keyword)})
          entries)))

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

(defn merge-state-status->behind?
  "Map mergeStateStatus to a boolean indicating if the PR is behind main.
   Accepts strings or keywords. Values: BEHIND, CLEAN, DIRTY, BLOCKED, HAS_HOOKS, UNKNOWN, UNSTABLE."
  [merge-state-status]
  (let [s (some-> merge-state-status name str/upper-case)]
    (contains? #{"BEHIND" "DIRTY"} s)))

(defn provider-pr->train-pr
  "Convert a GitHub provider PR map to a normalized TrainPR map.
   Adds :pr/repo when repo is provided.
   Includes :pr/ci-checks for individual check results,
   :pr/merge-state / :pr/behind-main? for branch-behind-main detection,
   and :pr/additions / :pr/deletions / :pr/changed-files-count for risk assessment."
  ([pr] (provider-pr->train-pr pr nil))
  ([pr repo]
   (let [merge-state (:mergeStateStatus pr)
         additions   (get pr :additions 0)
         deletions   (get pr :deletions 0)
         changed     (get pr :changedFiles 0)
         author-login (get-in pr [:author :login] "")]
     (cond-> {:pr/number             (:number pr)
              :pr/title              (:title pr)
              :pr/url                (:url pr)
              :pr/branch             (:headRefName pr)
              :pr/status             (pr-status-from-provider pr)
              :pr/merged-at          (:mergedAt pr)
              :pr/ci-status          (check-rollup->ci-status (:statusCheckRollup pr))
              :pr/ci-checks          (check-rollup->ci-checks (:statusCheckRollup pr))
              :pr/merge-state        (some-> merge-state str str/upper-case keyword)
              :pr/behind-main?       (merge-state-status->behind? merge-state)
              :pr/additions          additions
              :pr/deletions          deletions
              :pr/changed-files-count changed
              :pr/author             author-login}
       repo (assoc :pr/repo repo)))))

;------------------------------------------------------------------------------ Layer 2
;; GitLab MR status mapping + unified conversion

(defn gitlab-status
  [mr]
  (let [state (some-> (:state mr) str str/lower-case)
        merged-at (:merged_at mr)
        closed-at (:closed_at mr)
        draft? (or (true? (:draft mr))
                   (true? (:work_in_progress mr))
                   (str/starts-with? (str/lower-case (or (:title mr) "")) "draft:"))
        merge-status (some-> (:merge_status mr) str str/lower-case)
        conflicts? (true? (:has_conflicts mr))]
    (cond
      ;; merged_at is definitive — catches cases where state lags behind
      (some? merged-at) :merged
      (= "merged" state) :merged
      (some? closed-at) :closed
      (not= "opened" state) :closed
      draft? :draft
      conflicts? :changes-requested
      (contains? #{"can_be_merged" "mergeable"} merge-status) :merge-ready
      :else :open)))

(defn gitlab-ci-status
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

(defn gitlab-merge-state
  "Map GitLab merge_status to a keyword merge state (GitHub parity)."
  [mr]
  (let [ms (some-> (:merge_status mr) str str/lower-case)]
    (case ms
      ("can_be_merged" "mergeable") :CLEAN
      "cannot_be_merged"            :DIRTY
      "unchecked"                   :UNKNOWN
      "checking"                    :UNKNOWN
      nil)))

(defn gitlab-behind-main?
  "Estimate if a GitLab MR is behind the target branch.
   GitLab signals this via merge_status and has_conflicts."
  [mr]
  (let [ms (some-> (:merge_status mr) str str/lower-case)]
    (or (true? (:has_conflicts mr))
        (= "cannot_be_merged" ms))))

(defn gitlab-mr->train-pr
  "Convert a GitLab MR map to a normalized TrainPR map.
   Extracts all available fields from the MR list response including
   author, merge state, timestamps, and diff stats (when enriched)."
  [mr repo]
  (let [author-login (or (get-in mr [:author :username]) "")
        changes      (:changes_count mr)
        ;; diff stats are only present when enriched via single-MR fetch
        additions    (:additions mr)
        deletions    (:deletions mr)]
    {:pr/number              (:iid mr)
     :pr/title               (:title mr)
     :pr/url                 (:web_url mr)
     :pr/branch              (:source_branch mr)
     :pr/status              (gitlab-status mr)
     :pr/ci-status           (gitlab-ci-status mr)
     :pr/repo                repo
     :pr/author              author-login
     :pr/merged-at           (:merged_at mr)
     :pr/merge-state         (gitlab-merge-state mr)
     :pr/behind-main?        (gitlab-behind-main? mr)
     :pr/additions           (or additions 0)
     :pr/deletions           (or deletions 0)
     :pr/changed-files-count (if changes
                               (if (string? changes)
                                 (try (Integer/parseInt changes) (catch Exception _ 0))
                                 (int changes))
                               0)}))

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
