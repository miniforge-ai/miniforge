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
(ns ai.miniforge.cli.app-config.profile
  "CLI app identity: the resource-backed profile (name, description,
   home-dir name, ...) and the PR-monitor / status config blocks that ride
   the same classpath resource. Split out of `ai.miniforge.cli.app-config`
   (rule 210: the combined namespace measured 6 real layers, max 3) —
   identity resolution is layer-coherent on its own, while the home-dir
   and filesystem-layout logic that builds on it moved to the sibling
   `ai.miniforge.cli.app-config.paths`. `ai.miniforge.cli.app-config`
   re-exports every symbol here as its stable public surface."
  (:require
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
