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
(ns ai.miniforge.release-executor.pr-lookup
  "Find the PR a release branch already has, so a retried or resumed
   release reuses it instead of opening a second one, and name the head a
   new PR is opened from. Shared by the host (git) and sandbox backends;
   each runs `lookup-argv` its own way."
  (:require [ai.miniforge.release-executor.push-auth :as push-auth]
            [ai.miniforge.release-executor.result :as result]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private state-order
  "Which PR to prefer when a branch has several: an open one, then a
   merged one, then a closed one."
  {"OPEN" 0 "MERGED" 1 "CLOSED" 2})

(defn ^{:stratum 0} lookup-argv
  "gh command listing every PR, in any state, whose head is `branch`: one
   tab-separated `number url state head-sha` line per PR, newest first,
   nothing when there is none."
  [branch]
  ["gh" "pr" "list" "--head" branch "--state" "all" "--limit" "20"
   "--json" "number,url,state,headRefOid"
   "--jq" ".[] | [.number, .url, .state, .headRefOid] | @tsv"])

(def ^{:stratum 0} branch-argv
  "git command printing the checked-out branch. It prints nothing for a
   detached HEAD."
  ["git" "branch" "--show-current"])

(def ^{:stratum 0} push-url-argv
  "git command printing the URL a push to origin goes to: where release
   pushes its branch, so the URL's owner is the PR head's owner."
  ["git" "remote" "get-url" "--push" "origin"])

(defn- ^{:stratum 0} url-owner
  "The owner in an SSH or HTTPS URL of an `<owner>/<repo>` path, or nil."
  [url]
  (some->> (push-auth/https-url url)
           second
           (re-matches #"https://[^/]+/([A-Za-z0-9][A-Za-z0-9-]*)/[^/]+")
           second))

(defn- ^{:stratum 0} stopped?
  "True for a shell-result whose command was interrupted or timed out."
  [r]
  (boolean (or (:interrupted? r) (:timed-out? r))))

(defn- ^{:stratum 0} lookup-output
  "Trimmed output of a succeeded lookup, or nil."
  [r]
  (when (result/succeeded? r)
    (not-empty (str/trim (get r :output "")))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} head-ref
  "The `gh pr create --head` value for `branch` pushed to `push-url`:
   `<owner>:<branch>` when the URL names an owner, else `branch` alone.
   gh reads a bare branch as a branch in the base repository; the owner
   keeps a fork's PR pointing at the fork when gh resolves the base to
   another remote."
  [push-url branch]
  (if-let [owner (url-owner push-url)]
    (str owner ":" branch)
    branch))

(defn ^{:stratum 1} parse-lookup
  "The branch's PR from `lookup-argv` output as {:pr-number :pr-url
   :pr-state :pr-head-sha}: an open PR before a merged one before a closed
   one, the newest within a state. nil when the output lists none."
  [output]
  (->> (str/split-lines (str output))
       (keep (fn [line]
               (let [[number url state head-sha] (str/split (str/trim line) #"\t")
                     n (some-> number parse-long)]
                 (when (and n (not (str/blank? url)) (contains? state-order state))
                   {:pr-number n :pr-url url :pr-state state :pr-head-sha head-sha}))))
       ;; sort-by is stable, so gh's newest-first order holds within a state.
       (sort-by (comp state-order :pr-state))
       first))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} head-from-lookups
  "The PR head from create-pr!'s two git lookups, the shell-results of
   `branch-argv` and `push-url-argv`. {:stopped r} when either was
   interrupted or timed out: gh must not start. Else {:branch b :head h},
   both nil when no branch was read."
  [branch-r url-r]
  (if-let [stopped (first (filter stopped? [branch-r url-r]))]
    {:stopped stopped}
    (let [branch (lookup-output branch-r)]
      {:branch branch
       :head   (some->> branch (head-ref (lookup-output url-r)))})))
