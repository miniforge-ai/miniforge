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
  "Project-composed CLI app identity and filesystem layout. A stable facade
   over `ai.miniforge.cli.app-config.profile` (resource-backed identity)
   and `ai.miniforge.cli.app-config.paths` (home-dir resolution and
   filesystem layout) — split out (rule 210: the combined namespace
   measured 6 real layers, max 3). Every symbol here is a plain re-export;
   the real logic and each sibling's own layer structure live in those two
   namespaces. Kept as a facade, rather than moving callers over, because
   this namespace is required directly (fully-qualified) across bases,
   components, and projects — re-exporting keeps every one of those call
   sites unchanged."
  (:require
   [ai.miniforge.cli.app-config.profile :as profile]
   [ai.miniforge.cli.app-config.paths :as paths]))

;------------------------------------------------------------------------------ Layer 0

;; Re-exported from ai.miniforge.cli.app-config.profile
(def ^{:stratum 0} app-config-resource
  "Classpath resource path for CLI app identity."
  profile/app-config-resource)

(def ^{:stratum 0} default-status-config
  "Defaults for workflow status rendering and health classification."
  profile/default-status-config)

(def ^{:stratum 0} getenv
  "Environment-variable lookup seam. Public so tests can rebind it via
   `with-redefs` when validating MINIFORGE_HOME resolution; not part of
   the external API."
  profile/getenv)

(def ^{:stratum 0} app-profile
  "Resolve the active CLI app profile from the classpath."
  profile/app-profile)

(def ^{:stratum 0} pr-monitor-config
  "Resolve PR monitor CLI config from the classpath."
  profile/pr-monitor-config)

(def ^{:stratum 0} status-config
  "Resolve workflow status CLI config from the classpath."
  profile/status-config)

(def ^{:stratum 0} binary-name profile/binary-name)

(def ^{:stratum 0} display-name profile/display-name)

(def ^{:stratum 0} description profile/description)

(def ^{:stratum 0} system-check-title profile/system-check-title)

(def ^{:stratum 0} home-dir-name profile/home-dir-name)

(def ^{:stratum 0} tui-package profile/tui-package)

(def ^{:stratum 0} help-examples profile/help-examples)

;; Re-exported from ai.miniforge.cli.app-config.paths
(def ^{:stratum 0} default-home-dir
  "Profile-derived default home directory. Public so tests can rebind
   it via `with-redefs`; not part of the external API."
  paths/default-home-dir)

(def ^{:stratum 0} command-string
  "Build a CLI command string prefixed with the active binary name."
  paths/command-string)

(def ^{:stratum 0} home-dir paths/home-dir)

(def ^{:stratum 0} config-path paths/config-path)

(def ^{:stratum 0} artifacts-dir paths/artifacts-dir)

(def ^{:stratum 0} worktrees-dir paths/worktrees-dir)

(def ^{:stratum 0} events-dir paths/events-dir)

(def ^{:stratum 0} logs-dir paths/logs-dir)

(def ^{:stratum 0} dashboard-port-file paths/dashboard-port-file)

(def ^{:stratum 0} state-file paths/state-file)
