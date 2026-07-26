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
(ns ai.miniforge.cli.app-config
  "Project-composed CLI app identity and filesystem layout."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.cli.resource-config :as resource-config]))

;------------------------------------------------------------------------------ Layer 0

;; Resource loading
(def ^{:stratum 0} app-config-resource
  "Classpath resource path for CLI app identity."
  "config/cli/app.edn")

(def ^{:stratum 0} default-status-config
  "Defaults for workflow status rendering and health classification."
  {:running-stale-threshold-ms 300000})

(defn- ^{:stratum 0} normalize-profile
  [profile]
  (-> profile
      (update :help-examples #(vec (or % [])))))

(defn ^{:stratum 0} getenv
  "Environment-variable lookup seam. Public so tests can rebind it via
   `with-redefs` when validating MINIFORGE_HOME resolution; not part of
   the external API."
  [var-name]
  (System/getenv var-name))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} app-profile
  "Resolve the active CLI app profile from the classpath."
  []
  (-> (resource-config/merged-resource-config app-config-resource
                                              :cli-app/profile
                                              {})
      normalize-profile))

(defn ^{:stratum 1} pr-monitor-config
  "Resolve PR monitor CLI config from the classpath."
  []
  (resource-config/merged-resource-config app-config-resource
                                          :cli-app/pr-monitor
                                          {}))

(defn ^{:stratum 1} status-config
  "Resolve workflow status CLI config from the classpath."
  []
  (resource-config/merged-resource-config app-config-resource
                                          :cli-app/status
                                          default-status-config))

;------------------------------------------------------------------------------ Layer 2

;; Identity helpers
(defn ^{:stratum 2} binary-name []
  (:name (app-profile)))

(defn ^{:stratum 2} display-name []
  (:display-name (app-profile)))

(defn ^{:stratum 2} description []
  (:description (app-profile)))

(defn ^{:stratum 2} system-check-title []
  (:system-check-title (app-profile)))

(defn ^{:stratum 2} home-dir-name []
  (:home-dir-name (app-profile)))

(defn ^{:stratum 2} tui-package []
  (:tui-package (app-profile)))

(defn ^{:stratum 2} help-examples []
  (:help-examples (app-profile)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} default-home-dir
  "Profile-derived default home directory. Public so tests can rebind
   it via `with-redefs`; not part of the external API."
  []
  (str (fs/home) "/" (home-dir-name)))

(defn ^{:stratum 3} command-string
  "Build a CLI command string prefixed with the active binary name."
  [& parts]
  (str/join " " (cons (binary-name) (remove str/blank? parts))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} home-dir []
  (or (getenv "MINIFORGE_HOME")
      (default-home-dir)))

;------------------------------------------------------------------------------ Layer 5

;; Filesystem layout helpers
(defn ^{:stratum 5} config-path []
  (str (home-dir) "/config.edn"))

(defn ^{:stratum 5} artifacts-dir []
  (str (home-dir) "/artifacts"))

(defn ^{:stratum 5} worktrees-dir []
  (str (home-dir) "/worktrees"))

(defn ^{:stratum 5} events-dir []
  (str (home-dir) "/events"))

(defn ^{:stratum 5} logs-dir []
  (str (home-dir) "/logs"))

(defn ^{:stratum 5} dashboard-port-file []
  (str (home-dir) "/dashboard.port"))

(defn ^{:stratum 5} state-file []
  (str (home-dir) "/state.edn"))
