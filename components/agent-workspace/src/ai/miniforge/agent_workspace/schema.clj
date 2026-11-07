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
(ns ai.miniforge.agent-workspace.schema
  "Versioned values of the multi-repository workspace: which repositories a
   task clones into one environment, the task branch and base each starts
   from, and what was delivered for each."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every workspace request names; any other is refused."
  "agent-workspace/v1")

(def ^{:stratum 0} NonBlank [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} RepoId
  "`owner/name`: what the allowed set, every effect key and the clone URL
   name. Neither part may be `.` or `..`, so a checkout path stays under
   the workspace root."
  [:re #"\A(?!\.{1,2}/)[A-Za-z0-9_.-]+/(?!\.{1,2}\z)[A-Za-z0-9_.-]+\z"])

(def ^{:stratum 0} RefName
  "A branch, tag or commit to start from; never an option, so it cannot
   start with `-`."
  [:re #"\A[A-Za-z0-9_][A-Za-z0-9_./-]*\z"])

(def ^{:stratum 0} AbsolutePath [:re #"\A/[^\x00]*\z"])

(def ^{:stratum 0} GitSha [:re #"\A[0-9a-f]{40}\z"])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Repo
  "A repository is named only by id; the controller derives its URL, so a
   request cannot point an allowed id at another repository."
  [:map {:closed true}
   [:repo/id RepoId]
   [:repo/base-ref RefName]])

(def ^{:stratum 1} Checkout
  "One cloned repository: where it is, the base it started from, and its
   task branch."
  [:map {:closed true}
   [:repo/id RepoId]
   [:repo/path NonBlank]
   [:repo/base-sha GitSha]
   [:repo/task-branch NonBlank]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Request
  [:map {:closed true}
   [:workspace/contract-version [:= contract-version]]
   [:workspace/root AbsolutePath]
   [:workspace/repos [:vector {:min 1} Repo]]])

(def ^{:stratum 2} Workspace
  [:map {:closed true}
   [:workspace/lease [:map-of :keyword any?]]
   [:workspace/root AbsolutePath]
   [:workspace/checkouts [:vector Checkout]]])
