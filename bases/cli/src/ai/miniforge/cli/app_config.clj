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

(def app-config-resource
  "Classpath resource path for CLI app identity."
  "config/cli/app.edn")

(defn- normalize-profile
  [profile]
  (-> profile
      (update :help-examples #(vec (or % [])))))

(defn app-profile
  "Resolve the active CLI app profile from the classpath."
  []
  (-> (resource-config/merged-resource-config app-config-resource
                                              :cli-app/profile
                                              {})
      normalize-profile))

(defn pr-monitor-config
  "Resolve PR monitor CLI config from the classpath."
  []
  (resource-config/merged-resource-config app-config-resource
                                          :cli-app/pr-monitor
                                          {}))

;------------------------------------------------------------------------------ Layer 1
;; Identity helpers

(defn binary-name []
  (:name (app-profile)))

(defn display-name []
  (:display-name (app-profile)))

(defn description []
  (:description (app-profile)))

(defn system-check-title []
  (:system-check-title (app-profile)))

(defn home-dir-name []
  (:home-dir-name (app-profile)))

(defn getenv
  "Environment-variable lookup seam. Public so tests can rebind it via
   `with-redefs` when validating MINIFORGE_HOME resolution; not part of
   the external API."
  [var-name]
  (System/getenv var-name))

(defn default-home-dir
  "Profile-derived default home directory. Public so tests can rebind
   it via `with-redefs`; not part of the external API."
  []
  (str (fs/home) "/" (home-dir-name)))

(defn home-dir []
  (or (getenv "MINIFORGE_HOME")
      (default-home-dir)))

(defn tui-package []
  (:tui-package (app-profile)))

(defn help-examples []
  (:help-examples (app-profile)))

(defn command-string
  "Build a CLI command string prefixed with the active binary name."
  [& parts]
  (str/join " " (cons (binary-name) (remove str/blank? parts))))

;------------------------------------------------------------------------------ Layer 2
;; Filesystem layout helpers

(defn config-path []
  (str (home-dir) "/config.edn"))

(defn artifacts-dir []
  (str (home-dir) "/artifacts"))

(defn worktrees-dir []
  (str (home-dir) "/worktrees"))

(defn events-dir []
  (str (home-dir) "/events"))

(defn logs-dir []
  (str (home-dir) "/logs"))

(defn commands-dir
  ([] (str (home-dir) "/commands"))
  ([workflow-id] (str (commands-dir) "/" workflow-id)))

(defn dashboard-port-file []
  (str (home-dir) "/dashboard.port"))

(defn state-file []
  (str (home-dir) "/state.edn"))
