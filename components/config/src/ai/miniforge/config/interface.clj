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
   [ai.miniforge.config.profile :as profile]))

;; Re-export public functions
(def default-user-config-path user/default-user-config-path)
(def default-config user/default-config)
(def miniforge-home user/miniforge-home)

(defn load-user-config
  "Load user configuration from file."
  ([] (user/load-user-config))
  ([path] (user/load-user-config path)))

(defn load-merged-config
  "Load and merge all configuration sources (user > env > defaults)."
  ([] (user/load-merged-config))
  ([path] (user/load-merged-config path)))

(defn save-user-config
  "Save user configuration to file."
  ([config] (user/save-user-config config))
  ([config path] (user/save-user-config config path)))

(defn init-user-config
  "Initialize user config file with defaults."
  ([] (user/init-user-config))
  ([path] (user/init-user-config path)))

(defn update-user-config
  "Update a specific key in user config."
  ([key-path value] (user/update-user-config key-path value))
  ([key-path value path] (user/update-user-config key-path value path)))

(defn reset-user-config
  "Reset user config to defaults."
  ([] (user/reset-user-config))
  ([path] (user/reset-user-config path)))

(defn edit-user-config
  "Open user config in $EDITOR."
  ([] (user/edit-user-config))
  ([path] (user/edit-user-config path)))

(defn merge-configs
  "Merge configurations with precedence."
  [& configs]
  (apply user/merge-configs configs))

;; Governance config functions
(defn load-governance-config
  "Load governance config with full merge chain (resource -> profile -> user -> pack -> compile)."
  ([config-key] (governance/load-governance-config config-key))
  ([config-key opts] (governance/load-governance-config config-key opts)))

(defn apply-pack-overrides
  "Apply pack config overrides with safety checks."
  [config-key base-config pack]
  (governance/apply-pack-overrides config-key base-config pack))

;; Digest functions
(defn sha256-hex
  "Compute SHA-256 hex digest of a string."
  [content]
  (digest/sha256-hex content))

(defn verify-governance-file
  "Verify governance file content against digest manifest."
  [config-key content]
  (digest/verify-governance-file config-key content))

;; Profile functions
(def profile-path profile/profile-path)

(defn load-profile
  "Load user profile from ~/.miniforge/profile.edn."
  ([] (profile/load-profile))
  ([path] (profile/load-profile path)))

(defn validate-profile
  "Validate a profile map. Returns {:valid? bool :errors [...]}."
  [p]
  (profile/validate-profile p))

(defn resolve-token
  "Resolve git token for a host kind using profile + env var chain."
  [host-kind & [opts]]
  (profile/resolve-token host-kind opts))
