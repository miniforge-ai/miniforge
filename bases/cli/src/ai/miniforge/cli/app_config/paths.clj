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
(ns ai.miniforge.cli.app-config.paths
  "CLI home-directory resolution and the filesystem layout (config file,
   artifacts/worktrees/events/logs dirs, ...) built on it. Split out of
   `ai.miniforge.cli.app-config` (rule 210: the combined namespace measured
   6 real layers, max 3). Builds on the resource-backed identity in the
   sibling `ai.miniforge.cli.app-config.profile` (binary name, home-dir
   name, the env-var lookup seam). `ai.miniforge.cli.app-config`
   re-exports every symbol here as its stable public surface."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config.profile :as profile]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} default-home-dir
  "Profile-derived default home directory. Public so tests can rebind
   it via `with-redefs`; not part of the external API."
  []
  (str (fs/home) "/" (profile/home-dir-name)))

(defn ^{:stratum 0} command-string
  "Build a CLI command string prefixed with the active binary name."
  [& parts]
  (str/join " " (cons (profile/binary-name) (remove str/blank? parts))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} home-dir []
  (or (profile/getenv "MINIFORGE_HOME")
      (default-home-dir)))

;------------------------------------------------------------------------------ Layer 2

;; Filesystem layout helpers
(defn ^{:stratum 2} config-path []
  (str (home-dir) "/config.edn"))

(defn ^{:stratum 2} artifacts-dir []
  (str (home-dir) "/artifacts"))

(defn ^{:stratum 2} worktrees-dir []
  (str (home-dir) "/worktrees"))

(defn ^{:stratum 2} events-dir []
  (str (home-dir) "/events"))

(defn ^{:stratum 2} logs-dir []
  (str (home-dir) "/logs"))

(defn ^{:stratum 2} dashboard-port-file []
  (str (home-dir) "/dashboard.port"))

(defn ^{:stratum 2} state-file []
  (str (home-dir) "/state.edn"))
