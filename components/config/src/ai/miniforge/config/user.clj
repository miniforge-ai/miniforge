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

(ns ai.miniforge.config.user
  "User configuration management for Miniforge.

   Provides loading, merging, and saving of user configuration.
   Configuration precedence: repo > user > env > defaults.

   Default configuration is loaded from resources/config/default-user-config.edn"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [babashka.fs :as fs]
   [babashka.process]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and utilities

(defn miniforge-home
  "Return the miniforge home directory.
   Resolution: MINIFORGE_HOME env > ~/.miniforge"
  []
  (or (System/getenv "MINIFORGE_HOME")
      (str (System/getProperty "user.home") "/.miniforge")))

(def default-user-config-path
  "Default location for user config file."
  (str (miniforge-home) "/config.edn"))

(def ^:private default-config-resource-path
  "Primary resource path for shipped user config defaults."
  "config/default-user-config.edn")

(def ^:private fallback-config-resource-path
  "Fallback resource path when the primary config cannot be loaded."
  "config/default-user-config-fallback.edn")

(defn find-resource
  "Resource lookup seam. Public so tests can rebind it via `with-redefs`
   when validating the fallback resource path; not part of the external
   API."
  [resource-path]
  (io/resource resource-path))

(defn- read-config-resource
  "Read an EDN config resource, returning nil on missing resource or parse failure."
  [resource-path]
  (when-let [resource (find-resource resource-path)]
    (try
      (edn/read-string (slurp resource))
      (catch Exception _e
        nil))))

(defn load-default-config
  "Load default configuration from resources.

   Returns: Default config map or resource-backed fallback."
  []
  (or (some-> (read-config-resource default-config-resource-path) :config)
      (some-> (read-config-resource fallback-config-resource-path) :config)
      {}))

(def default-config
  "Default configuration values loaded from resources/config/default-user-config.edn"
  (load-default-config))

(defn read-edn-file
  "Safely read an EDN file, returning nil on error."
  [path]
  (try
    (when (and path (fs/exists? path))
      (edn/read-string (slurp path)))
    (catch Exception _e
      nil)))

(defn write-edn-file
  "Write EDN data to a file, creating parent directories if needed."
  [path data]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (clojure.pprint/pprint data))))

;------------------------------------------------------------------------------ Layer 0
;; Repo-level configuration

(def repo-config-dir-name
  "Directory name for repo-level miniforge configuration."
  ".miniforge")

(defn repo-config-path
  "Return the path to .miniforge/config.edn in the given working directory.

   Uses the JVM's current working directory by default. Pass an explicit
   root to override — useful in tests or when the CWD differs from the
   repository root.

   Arguments:
   - working-dir - Optional root directory string (defaults to user.dir property)"
  ([]
   (repo-config-path (System/getProperty "user.dir")))
  ([working-dir]
   (str working-dir java.io.File/separator
        repo-config-dir-name java.io.File/separator
        "config.edn")))

(defn load-repo-config
  "Load repo-level configuration from .miniforge/config.edn.

   Reads from the current working directory by default. Returns nil when
   the file does not exist or cannot be parsed — never throws.

   This layer lets individual repositories declare :policy-packs settings,
   governance overrides, or other project-specific config without touching
   the user's global ~/.miniforge/config.edn.

   Arguments:
   - path - Optional explicit path (defaults to (repo-config-path))"
  ([] (load-repo-config (repo-config-path)))
  ([path]
   (read-edn-file path)))

;------------------------------------------------------------------------------ Layer 1
;; Environment variable overrides

(defn get-env-var
  "Get environment variable value."
  [var-name]
  (System/getenv var-name))

(defn parse-env-value
  "Parse environment variable value (string -> EDN)."
  [value]
  (when value
    (try
      (edn/read-string value)
      (catch Exception _
        value))))

(defn apply-env-overrides
  "Apply environment variable overrides to config.

   Supports these env vars:
   - MINIFORGE_LLM_BACKEND
   - MINIFORGE_LLM_MODEL
   - MINIFORGE_LLM_TIMEOUT
   - MINIFORGE_LLM_LINE_TIMEOUT
   - MINIFORGE_LLM_MAX_TOKENS
   - MINIFORGE_AGENT_THINKING_MODEL
   - MINIFORGE_AGENT_EXECUTION_MODEL
   - MINIFORGE_MAX_ITERATIONS
   - MINIFORGE_MAX_TOKENS
   - MINIFORGE_FAILURE_STRATEGY
   - MINIFORGE_ARTIFACTS_DIR
   - MINIFORGE_MODEL_SELECTION_ENABLED
   - MINIFORGE_MODEL_SELECTION_STRATEGY
   - MINIFORGE_MODEL_SELECTION_COST_LIMIT
   - MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL
   - MINIFORGE_SELF_HEALING_ENABLED
   - MINIFORGE_SELF_HEALING_AUTO_APPLY
   - MINIFORGE_BACKEND_AUTO_SWITCH
   - MINIFORGE_BACKEND_HEALTH_THRESHOLD"
  [config]
  (cond-> config
    ;; LLM settings
    (get-env-var "MINIFORGE_LLM_BACKEND")
    (assoc-in [:llm :backend] (keyword (get-env-var "MINIFORGE_LLM_BACKEND")))

    (get-env-var "MINIFORGE_LLM_MODEL")
    (assoc-in [:llm :model] (get-env-var "MINIFORGE_LLM_MODEL"))

    (get-env-var "MINIFORGE_LLM_TIMEOUT")
    (assoc-in [:llm :timeout-ms] (parse-env-value (get-env-var "MINIFORGE_LLM_TIMEOUT")))

    (get-env-var "MINIFORGE_LLM_LINE_TIMEOUT")
    (assoc-in [:llm :line-timeout-ms] (parse-env-value (get-env-var "MINIFORGE_LLM_LINE_TIMEOUT")))

    (get-env-var "MINIFORGE_LLM_MAX_TOKENS")
    (assoc-in [:llm :max-tokens] (parse-env-value (get-env-var "MINIFORGE_LLM_MAX_TOKENS")))

    ;; Agent model defaults
    (get-env-var "MINIFORGE_AGENT_THINKING_MODEL")
    (assoc-in [:agents :default-models :thinking] (get-env-var "MINIFORGE_AGENT_THINKING_MODEL"))

    (get-env-var "MINIFORGE_AGENT_EXECUTION_MODEL")
    (assoc-in [:agents :default-models :execution] (get-env-var "MINIFORGE_AGENT_EXECUTION_MODEL"))

    ;; Workflow settings
    (get-env-var "MINIFORGE_MAX_ITERATIONS")
    (assoc-in [:workflow :max-iterations] (parse-env-value (get-env-var "MINIFORGE_MAX_ITERATIONS")))

    (get-env-var "MINIFORGE_MAX_TOKENS")
    (assoc-in [:workflow :max-tokens] (parse-env-value (get-env-var "MINIFORGE_MAX_TOKENS")))

    (get-env-var "MINIFORGE_FAILURE_STRATEGY")
    (assoc-in [:workflow :failure-strategy] (keyword (get-env-var "MINIFORGE_FAILURE_STRATEGY")))

    ;; Artifact settings
    (get-env-var "MINIFORGE_ARTIFACTS_DIR")
    (assoc-in [:artifacts :dir] (get-env-var "MINIFORGE_ARTIFACTS_DIR"))

    ;; Model selection settings
    (get-env-var "MINIFORGE_MODEL_SELECTION_ENABLED")
    (assoc-in [:model-selection :enabled] (parse-env-value (get-env-var "MINIFORGE_MODEL_SELECTION_ENABLED")))

    (get-env-var "MINIFORGE_MODEL_SELECTION_STRATEGY")
    (assoc-in [:model-selection :strategy] (keyword (get-env-var "MINIFORGE_MODEL_SELECTION_STRATEGY")))

    (get-env-var "MINIFORGE_MODEL_SELECTION_COST_LIMIT")
    (assoc-in [:model-selection :cost-limit-per-task] (parse-env-value (get-env-var "MINIFORGE_MODEL_SELECTION_COST_LIMIT")))

    (get-env-var "MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL")
    (assoc-in [:model-selection :require-local] (parse-env-value (get-env-var "MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL")))

    ;; Self-healing settings
    (get-env-var "MINIFORGE_SELF_HEALING_ENABLED")
    (assoc-in [:self-healing :enabled] (parse-env-value (get-env-var "MINIFORGE_SELF_HEALING_ENABLED")))

    (get-env-var "MINIFORGE_SELF_HEALING_AUTO_APPLY")
    (assoc-in [:self-healing :workaround-auto-apply] (parse-env-value (get-env-var "MINIFORGE_SELF_HEALING_AUTO_APPLY")))

    (get-env-var "MINIFORGE_BACKEND_AUTO_SWITCH")
    (assoc-in [:self-healing :backend-auto-switch] (parse-env-value (get-env-var "MINIFORGE_BACKEND_AUTO_SWITCH")))

    (get-env-var "MINIFORGE_BACKEND_HEALTH_THRESHOLD")
    (assoc-in [:self-healing :backend-health-threshold] (parse-env-value (get-env-var "MINIFORGE_BACKEND_HEALTH_THRESHOLD")))))

;------------------------------------------------------------------------------ Layer 2
;; Config loading and merging

(defn load-user-config
  "Load user configuration from file.
   Returns nil if file doesn't exist."
  ([] (load-user-config default-user-config-path))
  ([path]
   (read-edn-file path)))

(defn merge-configs
  "Merge configurations with precedence: user > env > defaults.

   Returns fully merged configuration map."
  [& configs]
  (let [base (apply merge-with
                    (fn [v1 v2]
                      (if (map? v1)
                        (merge v1 v2)
                        v2))
                    configs)]
    (apply-env-overrides base)))

(defn load-merged-config
  "Load and merge all configuration sources.

   Precedence: user config > env vars > defaults."
  ([] (load-merged-config default-user-config-path))
  ([user-config-path]
   (let [user-config (load-user-config user-config-path)]
     (if user-config
       (merge-configs default-config user-config)
       (merge-configs default-config)))))

(defn load-merged-config-with-repo
  "Load and merge all configuration sources, including repo-level config.

   Precedence (lowest to highest):
   1. Defaults  — shipped classpath resource
   2. User      — ~/.miniforge/config.edn
   3. Repo      — .miniforge/config.edn in the current working directory
   4. Env vars  — MINIFORGE_* overrides applied last

   The repo layer lets per-project settings (e.g. :policy-packs overrides)
   take priority over the user's global preferences without modifying the
   global config file.

   Arguments:
   - user-path - Path to user config (defaults to ~/.miniforge/config.edn)
   - repo-path - Path to repo config (defaults to .miniforge/config.edn in CWD)"
  ([] (load-merged-config-with-repo default-user-config-path (repo-config-path)))
  ([user-path repo-path]
   (merge-configs default-config
                  (load-user-config user-path)
                  (load-repo-config repo-path))))

;------------------------------------------------------------------------------ Layer 3
;; Config saving and initialization

(defn save-user-config
  "Save user configuration to file."
  ([config] (save-user-config config default-user-config-path))
  ([config path]
   (write-edn-file path config)
   config))

(defn init-user-config
  "Initialize user config file with defaults if it doesn't exist.
   Returns path to config file."
  ([] (init-user-config default-user-config-path))
  ([path]
   (when-not (fs/exists? path)
     (save-user-config default-config path))
   path))

(defn update-user-config
  "Update a specific key in user config.
   Loads existing config, updates key, and saves.

   key-path: vector of keys (e.g., [:llm :backend])
   value: new value to set"
  ([key-path value]
   (update-user-config key-path value default-user-config-path))
  ([key-path value path]
   (let [current-config (or (load-user-config path) default-config)
         updated-config (assoc-in current-config key-path value)]
     (save-user-config updated-config path))))

(defn reset-user-config
  "Reset user config to defaults."
  ([] (reset-user-config default-user-config-path))
  ([path]
   (save-user-config default-config path)))

(defn edit-user-config
  "Open user config in $EDITOR.
   Creates config file if it doesn't exist.
   Returns path to config file."
  ([] (edit-user-config default-user-config-path))
  ([path]
   (init-user-config path)
   (let [editor (or (System/getenv "EDITOR") "vim")
         result (babashka.process/shell editor (str path))]
     (when (zero? (:exit result))
       path))))
