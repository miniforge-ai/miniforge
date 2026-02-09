(ns ai.miniforge.config.user
  "User configuration management for Miniforge.

   Provides loading, merging, and saving of user configuration.
   Configuration precedence: user > env > defaults.

   Default configuration is loaded from resources/config/default-user-config.edn"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [babashka.fs :as fs]
   [babashka.process]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and utilities

(def default-user-config-path
  "Default location for user config file."
  (str (fs/home) "/.miniforge/config.edn"))

(defn- load-default-config
  "Load default configuration from resources.

   Returns: Default config map or hardcoded fallback"
  []
  (if-let [resource (io/resource "config/default-user-config.edn")]
    (try
      (:config (edn/read-string (slurp resource)))
      (catch Exception _e
        ;; Fallback if resource loading fails
        {:llm {:backend :claude
               :model "claude-sonnet-4-20250514"
               :timeout-ms 300000
               :line-timeout-ms 60000
               :max-tokens 4000}
         :workflow {:max-iterations 50
                    :max-tokens 150000
                    :failure-strategy :retry}
         :artifacts {:dir (str (fs/home) "/.miniforge/artifacts")}
         :meta-loop {:enabled true
                     :max-convergence-iterations 10
                     :convergence-threshold 0.95}
         :model-selection {:enabled true
                           :strategy :automatic
                           :cost-limit-per-task 0.10
                           :prefer-speed false
                           :allow-downgrade true
                           :require-local false}
         :self-healing {:enabled true
                        :workaround-auto-apply true
                        :backend-auto-switch true
                        :backend-health-threshold 0.90
                        :backend-switch-cooldown-ms 1800000}}))
    ;; Fallback if resource not found
    {:llm {:backend :claude
           :model "claude-sonnet-4-20250514"
           :timeout-ms 300000
           :line-timeout-ms 60000
           :max-tokens 4000}
     :workflow {:max-iterations 50
                :max-tokens 150000
                :failure-strategy :retry}
     :artifacts {:dir (str (fs/home) "/.miniforge/artifacts")}
     :meta-loop {:enabled true
                 :max-convergence-iterations 10
                 :convergence-threshold 0.95}
     :model-selection {:enabled true
                       :strategy :automatic
                       :cost-limit-per-task 0.10
                       :prefer-speed false
                       :allow-downgrade true
                       :require-local false}
     :self-healing {:enabled true
                    :workaround-auto-apply true
                    :backend-auto-switch true
                    :backend-health-threshold 0.90
                    :backend-switch-cooldown-ms 1800000}}))

(def default-config
  "Default configuration values loaded from resources/config/default-user-config.edn"
  (load-default-config))

(defn- read-edn-file
  "Safely read an EDN file, returning nil on error."
  [path]
  (try
    (when (and path (fs/exists? path))
      (edn/read-string (slurp path)))
    (catch Exception _e
      nil)))

(defn- write-edn-file
  "Write EDN data to a file, creating parent directories if needed."
  [path data]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (clojure.pprint/pprint data))))

;------------------------------------------------------------------------------ Layer 1
;; Environment variable overrides

(defn- get-env-var
  "Get environment variable value."
  [var-name]
  (System/getenv var-name))

(defn- parse-env-value
  "Parse environment variable value (string -> EDN)."
  [value]
  (when value
    (try
      (edn/read-string value)
      (catch Exception _
        value))))

(defn- apply-env-overrides
  "Apply environment variable overrides to config.

   Supports these env vars:
   - MINIFORGE_LLM_BACKEND
   - MINIFORGE_LLM_MODEL
   - MINIFORGE_LLM_TIMEOUT
   - MINIFORGE_LLM_LINE_TIMEOUT
   - MINIFORGE_LLM_MAX_TOKENS
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
