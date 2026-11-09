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
(ns ai.miniforge.agent-workspace.git
  "The git command lines a workspace runs inside the environment, and the
   one runner for them. Every argument is a separate argv element, so
   nothing is shell-interpreted; a nonzero exit is an anomaly."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-workspace.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} branch-prefix
  "Task branches live under this prefix, so they never collide with a
   repository's own branches."
  "miniforge/task-")

(def ^{:stratum 0} branch-ref-prefix
  "Where local and pushed branches live; naming it in full keeps a branch
   from being read as a tag or a remote-tracking ref of the same name."
  "refs/heads/")

(defn ^{:stratum 0} repo-path
  "`root/owner/name`: `/` is in no part of an id, so two ids never share a path."
  [root repo-id]
  (str root "/" repo-id))

(defn ^{:stratum 0} repo-url
  "The one URL a repository id clones from and pushes to."
  [wc repo-id]
  (str (:workspace/repo-base-url wc) repo-id ".git"))

(defn ^{:stratum 0} clone-argv [url path] ["git" "clone" "--no-tags" "--quiet" "--" url path])

(defn ^{:stratum 0} fetch-argv
  "Fetch the base ref, so a branch other than the default, a tag or a
   commit can start the task branch; it lands in FETCH_HEAD."
  [path url base-ref]
  ["git" "-C" path "fetch" "--quiet" "--no-tags" "--" url base-ref])

(defn ^{:stratum 0} branch-argv [path branch] ["git" "-C" path "checkout" "--quiet" "-B" branch "FETCH_HEAD"])

(defn ^{:stratum 0} head-argv [path] ["git" "-C" path "rev-parse" "HEAD"])

(defn ^{:stratum 0} sha-of [result] (str/trim (str (:exec/stdout result))))

(defn ^{:stratum 0} git!
  [wc acting lease argv]
  (let [result (env/exec! (:workspace/environment wc) acting lease
                          {:exec/argv argv :exec/timeout-ms (:workspace/git-timeout-ms wc)})]
    (cond
      (anomaly/anomaly? result) result
      (zero? (:exec/exit result)) result
      :else (anomaly/sub-anomaly :fault :anomalies.agent-workspace/git-failed
                                 (msg/t :anomalies.agent-workspace/git-failed)
                                 {:git/argv argv :exec/exit (:exec/exit result)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} branch-head-argv
  "The task branch's commit, whatever the worker has checked out."
  [path branch]
  ["git" "-C" path "rev-parse" "--verify" "--quiet" (str branch-ref-prefix branch "^{commit}")])

(defn ^{:stratum 1} push-argv
  "Push the recorded commit to the task branch at the id's URL, not the
   branch's tip now or whatever `origin` names."
  [path url sha branch]
  ["git" "-C" path "push" "--quiet" url (str sha ":" branch-ref-prefix branch)])

(defn ^{:stratum 1} task-branch [task-id] (str branch-prefix task-id))
