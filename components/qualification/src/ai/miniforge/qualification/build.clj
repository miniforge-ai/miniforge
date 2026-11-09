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
(ns ai.miniforge.qualification.build
  "The identity of the build a qualification run used: the git commit,
   whether the tree was clean, and a hash of the dependency files. A build
   whose state cannot be read is reported as not clean, never as clean.

   git runs with this process's environment. A process started from a git
   hook inherits variables such as GIT_DIR that bind git to the hook's
   repository; the launcher must strip them, as the test launchers do."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} dependency-files
  "Files whose content decides what the workspace resolves and builds."
  ["deps.edn" "workspace.edn"])

(defn- ^{:stratum 0} git-output
  "Trimmed stdout of `git args...` run in `root`, or nil when git exits
   nonzero or cannot be started."
  [root & args]
  (try+
   (let [{:keys [exit out]} (apply shell/sh "git" (concat args [:dir root]))]
     (when (zero? exit) (str/trim out)))
   (catch java.io.IOException _
     nil)))

(defn- ^{:stratum 0} file-content
  "The content of `root`/`name`, or nil when the file does not exist."
  [root name]
  (let [file (io/file root name)]
    (when (.isFile file) (slurp file))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-sha
  "The commit checked out in `root`, or nil when it cannot be read."
  [root]
  (let [sha (git-output root "rev-parse" "HEAD")]
    (when (and sha (re-matches #"[0-9a-f]{40}" sha)) sha)))

(defn ^{:stratum 1} clean?
  "True only when git reports no changed or untracked files in `root`."
  [root]
  (= "" (git-output root "status" "--porcelain")))

(defn ^{:stratum 1} deps-hash
  "Content hash of the dependency files in `root`, keyed by file name; a
   missing file hashes as nil."
  [root]
  (content-hash/content-hash
   (into {} (map (juxt identity #(file-content root %))) dependency-files)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-identity
  "{:build/git-sha :build/clean? :build/deps-hash} for the workspace at
   `root`."
  [root]
  {:build/git-sha   (git-sha root)
   :build/clean?    (clean? root)
   :build/deps-hash (deps-hash root)})

(comment
  (build-identity "."))
