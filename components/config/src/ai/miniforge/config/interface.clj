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
(ns ai.miniforge.config.interface
  "Public interface for configuration management."
  (:require
   [ai.miniforge.config.user :as user]
   [ai.miniforge.config.governance :as governance]
   [ai.miniforge.config.digest :as digest]
   [ai.miniforge.config.profile :as profile]
   [ai.miniforge.config.resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

;; Re-export public functions
;; Generic classpath EDN config-resource loaders — the one home for the
;; load-a-component's-config-resource pattern. `load-config-resource`
;; fails fast (ex-info) on a missing/malformed/non-map resource or a
;; missing required key; `read-config-resource-or` is fail-open and
;; returns the supplied fallback on any error.
(def ^{:stratum 0} load-config-resource
  "Read an EDN config resource from the classpath, failing fast with a
   clear ex-info on a missing/malformed/non-map resource or a missing
   required key. Arities: [path], [path required-keys], and
   [path required-keys extra-ex-data]."
  resource/load-config-resource)

(def ^{:stratum 0} read-config-resource-or
  "Read an EDN config resource, returning `fallback` if the resource is
   absent, unreadable, malformed, or not a map (fail-open). Args:
   [path fallback]."
  resource/read-config-resource-or)

(def ^{:stratum 0} default-user-config-path
  "Default filesystem path string for the user config file
   (`<miniforge-home>/config.edn`). Computed once at load time."
  user/default-user-config-path)

(def ^{:stratum 0} default-config
  "Default configuration value: a map loaded at load time from the shipped
   classpath resource config/default-user-config.edn (with a fallback
   resource). Empty map {} if neither resource can be read."
  user/default-config)

(def ^{:stratum 0} miniforge-home
  "Function returning the miniforge home directory as a string.
   Args: none. Returns: MINIFORGE_HOME env var if set, else
   `<user.home>/.miniforge`. Never nil."
  user/miniforge-home)

(def ^{:stratum 0} repo-config-dir-name
  "String naming the repo-level config directory: \".miniforge\"."
  user/repo-config-dir-name)

(defn ^{:stratum 0} repo-config-path
  "Return the path to .miniforge/config.edn in the given working directory."
  ([] (user/repo-config-path))
  ([working-dir] (user/repo-config-path working-dir)))

(defn ^{:stratum 0} load-user-config
  "Load user configuration from file."
  ([] (user/load-user-config))
  ([path] (user/load-user-config path)))

(defn ^{:stratum 0} load-repo-config
  "Load repo-level configuration from .miniforge/config.edn.
   Returns nil if the file does not exist or cannot be parsed."
  ([] (user/load-repo-config))
  ([path] (user/load-repo-config path)))

(defn ^{:stratum 0} load-merged-config
  "Load and merge all configuration sources (user > env > defaults)."
  ([] (user/load-merged-config))
  ([path] (user/load-merged-config path)))

(defn ^{:stratum 0} load-merged-config-with-repo
  "Load and merge all configuration sources including repo-level config.
   Precedence: repo > user > env > defaults."
  ([] (user/load-merged-config-with-repo))
  ([user-path repo-path] (user/load-merged-config-with-repo user-path repo-path)))

(defn ^{:stratum 0} save-user-config
  "Save user configuration to file."
  ([config] (user/save-user-config config))
  ([config path] (user/save-user-config config path)))

(defn ^{:stratum 0} init-user-config
  "Initialize user config file with defaults."
  ([] (user/init-user-config))
  ([path] (user/init-user-config path)))

(defn ^{:stratum 0} update-user-config
  "Update a specific key in user config."
  ([key-path value] (user/update-user-config key-path value))
  ([key-path value path] (user/update-user-config key-path value path)))

(defn ^{:stratum 0} reset-user-config
  "Reset user config to defaults."
  ([] (user/reset-user-config))
  ([path] (user/reset-user-config path)))

(defn ^{:stratum 0} edit-user-config
  "Open user config in $EDITOR."
  ([] (user/edit-user-config))
  ([path] (user/edit-user-config path)))

(defn ^{:stratum 0} merge-configs
  "Merge configurations with precedence."
  [& configs]
  (apply user/merge-configs configs))

;; Governance config functions
(defn ^{:stratum 0} load-governance-config
  "Load governance config with full merge chain (resource -> profile -> user -> pack -> compile)."
  ([config-key] (governance/load-governance-config config-key))
  ([config-key opts] (governance/load-governance-config config-key opts)))

(defn ^{:stratum 0} apply-pack-overrides
  "Apply pack config overrides with safety checks."
  [config-key base-config pack]
  (governance/apply-pack-overrides config-key base-config pack))

;; Digest functions
(defn ^{:stratum 0} sha256-hex
  "Compute SHA-256 hex digest of a string."
  [content]
  (digest/sha256-hex content))

(defn ^{:stratum 0} verify-governance-file
  "Verify governance file content against digest manifest."
  [config-key content]
  (digest/verify-governance-file config-key content))

;; Profile functions
(def ^{:stratum 0} profile-path
  "Default filesystem path string for the user profile file
   (`<miniforge-home>/profile.edn`). Computed once at load time."
  profile/profile-path)

(defn ^{:stratum 0} load-profile
  "Load user profile from ~/.miniforge/profile.edn."
  ([] (profile/load-profile))
  ([path] (profile/load-profile path)))

(defn ^{:stratum 0} validate-profile
  "Validate a profile map. Returns {:valid? bool :errors [...]}."
  [p]
  (profile/validate-profile p))

(defn ^{:stratum 0} resolve-token
  "Resolve git token for a host kind using profile + env var chain."
  [host-kind & [opts]]
  (profile/resolve-token host-kind opts))
