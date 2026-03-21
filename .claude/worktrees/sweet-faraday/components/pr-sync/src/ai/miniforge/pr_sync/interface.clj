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

(ns ai.miniforge.pr-sync.interface
  "Public API for the PR sync component.

   Fleet config management, GitHub PR fetching, and status mapping.
   Both web-dashboard and TUI depend on this interface."
  (:require
   [ai.miniforge.pr-sync.core :as core]
   [ai.miniforge.pr-sync.status :as status]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Fleet config

(defn get-configured-repos
  "Get configured fleet repositories as normalized slugs.
   Returns [\"owner/repo\" ...]."
  ([] (core/get-configured-repos))
  ([path] (core/get-configured-repos path)))

(defn add-repo!
  "Add a repository slug (owner/name) to fleet configuration.
   Returns {:success? bool :added? bool :repo str :repos [...]}."
  ([repo-slug] (core/add-repo! repo-slug))
  ([repo-slug path] (core/add-repo! repo-slug path)))

(defn remove-repo!
  "Remove a repository slug from fleet configuration.
   Returns {:success? bool :removed? bool :repo str :repos [...]}."
  ([repo-slug] (core/remove-repo! repo-slug))
  ([repo-slug path] (core/remove-repo! repo-slug path)))

(defn discover-repos!
  "Discover repositories from a GitHub org/user and add them to fleet config.
   Options: :owner, :limit, :config-path."
  [opts]
  (core/discover-repos! opts))

(defn list-org-repos
  "List repository slugs from providers (read-only, no config change).
   Options: :owner, :limit, :provider (:github, :gitlab, :all).
   Returns {:success? bool :repos [\"owner/name\"|\"gitlab:group/name\" ...]}."
  [opts]
  (core/list-org-repos opts))

;; ─────────────────────────────────────────────────────────────────────────────
;; GitHub PR fetching

(defn fetch-open-prs
  "Fetch open PRs for a single repository via gh CLI.
   Returns {:success? bool :repo str :prs [TrainPR ...]}."
  [repo]
  (core/fetch-open-prs repo))

(defn fetch-prs-by-state
  "Fetch PRs for a single repo by state (:open, :closed, :merged, :all).
   Returns {:success? bool :repo str :prs [TrainPR ...]}."
  [repo state]
  (core/fetch-prs-by-state repo state))

(defn fetch-all-fleet-prs
  "Fetch PRs for all configured fleet repositories.
   Returns flat vector of TrainPR maps with :pr/repo set.
   Options: :config-path, :state (:open default, :closed, :merged, :all)."
  ([] (core/fetch-all-fleet-prs))
  ([opts] (core/fetch-all-fleet-prs opts)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Status mapping (pure)

(defn pr-status-from-provider
  "Map GitHub PR provider data to normalized PR status keyword.
   Input: map with :state, :isDraft, :reviewDecision keys."
  [pr]
  (status/pr-status-from-provider pr))

(defn check-rollup->ci-status
  "Map GitHub statusCheckRollup to normalized CI status keyword."
  [rollup]
  (status/check-rollup->ci-status rollup))

(defn check-rollup->ci-checks
  "Extract individual check results from GitHub statusCheckRollup.
   Returns vector of {:name str :conclusion kw :status kw}."
  [rollup]
  (status/check-rollup->ci-checks rollup))

(defn merge-state-status->behind?
  "Map GitHub mergeStateStatus to boolean indicating if PR is behind main."
  [merge-state-status]
  (status/merge-state-status->behind? merge-state-status))

(defn provider-pr->train-pr
  "Convert a GitHub provider PR map to a normalized TrainPR map."
  ([pr] (status/provider-pr->train-pr pr))
  ([pr repo] (status/provider-pr->train-pr pr repo)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Fleet config I/O (lower-level, for direct config access)

(defn load-fleet-config
  "Load fleet configuration from disk.
   Returns {:fleet {:repos [...]}} or default empty config."
  ([] (core/load-fleet-config))
  ([path] (core/load-fleet-config path)))

(defn succeeded?
  "Check if a pr-sync result map indicates success."
  [result]
  (core/succeeded? result))

(defn save-fleet-config!
  "Write fleet configuration to disk."
  ([config] (core/save-fleet-config! config))
  ([config path] (core/save-fleet-config! config path)))
