(ns ai.miniforge.cli.config
  "Enhanced configuration management for Miniforge CLI.

   Provides commands for viewing, editing, and managing configuration."
  (:require
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.backends :as backends]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration paths and utilities

(def default-user-config-path
  (app-config/config-path))

(def default-config
  {:llm {:backend :claude
         :model "claude-sonnet-4-20250514"
         :timeout-ms 300000
         :line-timeout-ms 60000
         :max-tokens 4000}
   :workflow {:max-iterations 50
              :max-tokens 150000
              :failure-strategy :retry}
   :artifacts {:dir (app-config/artifacts-dir)}
   :meta-loop {:enabled true
               :max-convergence-iterations 10
               :convergence-threshold 0.95}
   :dashboard {:port 7878
               :auto-open false}})

(defn style
  "Apply terminal styling using ANSI escape codes."
  [text color]
  (let [colors {:red "31" :green "32" :yellow "33" :cyan "36"}]
    (str "\033[" (get colors color "37") "m" text "\033[0m")))

(defn print-success [msg]
  (println (style msg :green)))

(defn print-error [msg]
  (println (style (messages/t :config/error-prefix {:message msg}) :red)))

(defn print-info [msg]
  (println (style msg :cyan)))

;------------------------------------------------------------------------------ Layer 1
;; Config file operations

(defn read-config-file
  "Read config file, returns nil if doesn't exist."
  [path]
  (when (fs/exists? path)
    (try
      (read-string (slurp path))
      (catch Exception e
        (println (style (messages/t :config/warning-read-failed {:message (.getMessage e)}) :yellow))
        nil))))

(defn write-config-file
  "Write config to file with pretty printing."
  [path config]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (pprint/pprint config))))

(defn load-merged-config
  "Load config with env var overrides."
  [path]
  (let [file-config (or (read-config-file path) {})
        merged (merge-with (fn [v1 v2]
                            (if (map? v1)
                              (merge v1 v2)
                              v2))
                          default-config
                          file-config)]
    ;; Apply env var overrides
    (cond-> merged
      (System/getenv "MINIFORGE_LLM_BACKEND")
      (assoc-in [:llm :backend] (keyword (System/getenv "MINIFORGE_LLM_BACKEND")))

      (System/getenv "MINIFORGE_LLM_MODEL")
      (assoc-in [:llm :model] (System/getenv "MINIFORGE_LLM_MODEL")))))

;------------------------------------------------------------------------------ Layer 2
;; Config display and manipulation

(defn format-config-value
  "Format a config value for display."
  [v]
  (cond
    (keyword? v) (name v)
    (string? v) v
    :else (pr-str v)))

(defn parse-config-key
  "Parse config key path (e.g., 'llm.backend' -> [:llm :backend])."
  [key-str]
  (when key-str
    (mapv keyword (str/split key-str #"\."))))

(defn parse-config-value
  "Parse config value from string."
  [value-str]
  (try
    (read-string value-str)
    (catch Exception _
      value-str)))

(defn display-config
  "Display configuration in human-readable format."
  [config config-path]
  (println)
  (println (style (messages/t :config/title) :cyan))
  (println)

  ;; LLM Settings
  (println (messages/t :config/section-llm))
  (println (messages/t :config/llm-backend
                       {:backend (format-config-value (get-in config [:llm :backend]))
                        :provider (get-in backends/backend-specs [(get-in config [:llm :backend]) :provider] "Unknown")}))
  (println (messages/t :config/llm-model {:model (get-in config [:llm :model])}))
  (println (messages/t :config/llm-max-tokens {:max-tokens (get-in config [:llm :max-tokens])}))
  (println (messages/t :config/llm-timeout {:minutes (quot (get-in config [:llm :timeout-ms]) 60000)}))
  (println)

  ;; Workflow Settings
  (println (messages/t :config/section-workflow))
  (println (messages/t :config/workflow-max-iterations {:max-iterations (get-in config [:workflow :max-iterations])}))
  (println (messages/t :config/workflow-max-tokens {:max-tokens (get-in config [:workflow :max-tokens])}))
  (println (messages/t :config/workflow-failure-strategy {:failure-strategy (format-config-value (get-in config [:workflow :failure-strategy]))}))
  (println)

  ;; Artifacts
  (println (messages/t :config/section-artifacts))
  (println (messages/t :config/artifacts-dir {:dir (get-in config [:artifacts :dir])}))
  (println)

  ;; Config file location
  (println (messages/t :config/section-config-files))
  (println (str (messages/t :config/user-config {:path config-path})
               (if (fs/exists? config-path)
                 (style (messages/t :config/user-config-exists) :green)
                 (style (messages/t :config/user-config-not-created) :yellow))))
  (println (messages/t :config/defaults-embedded))
  (println)

  ;; Env var hints
  (println (messages/t :config/section-env-overrides))
  (println (messages/t :config/env-backend))
  (println (messages/t :config/env-model))
  (println))

;------------------------------------------------------------------------------ Layer 3
;; Command implementations

(defn cmd-list
  "List all configuration values."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)
        config (load-merged-config config-path)]
    (display-config config config-path)))

(defn cmd-get
  "Get a specific configuration value."
  [opts]
  (let [key-str (:key opts)
        config-path (or (:config opts) default-user-config-path)]
    (if-not key-str
      (do
        (print-error (messages/t :config/get-missing-key))
        (println (messages/t :config/get-usage {:command (app-config/command-string "config get <key>")}))
        (println (messages/t :config/get-example {:command (app-config/command-string "config get llm.backend")})))
      (let [config (load-merged-config config-path)
            key-path (parse-config-key key-str)
            value (get-in config key-path)]
        (if value
          (println (pr-str value))
          (print-error (messages/t :config/get-not-found {:key key-str})))))))

(defn cmd-set
  "Set a configuration value."
  [opts]
  (let [key-str (:key opts)
        value-str (:value opts)
        config-path (or (:config opts) default-user-config-path)]
    (if (or (not key-str) (not value-str))
      (do
        (print-error (messages/t :config/set-missing-args))
        (println (messages/t :config/set-usage {:command (app-config/command-string "config set <key> <value>")}))
        (println (messages/t :config/set-example {:command (app-config/command-string "config set llm.backend openai")})))
      (let [user-config (or (read-config-file config-path) default-config)
            key-path (parse-config-key key-str)
            value (parse-config-value value-str)
            updated-config (assoc-in user-config key-path value)]
        (write-config-file config-path updated-config)
        (println)
        (print-success (messages/t :config/set-success {:key key-str :value (pr-str value)}))
        (println (messages/t :config/set-saved {:path config-path}))
        (println)))))

(defn cmd-init
  "Initialize user config file."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)]
    (if (fs/exists? config-path)
      (do
        (print-info (messages/t :config/init-exists {:path config-path}))
        (println (messages/t :config/init-modify-hint {:command (app-config/command-string "config set <key> <value>")})))
      (do
        (write-config-file config-path default-config)
        (println)
        (print-success (messages/t :config/init-created {:path config-path}))
        (println)
        (println (messages/t :config/init-edit-hint {:command (app-config/command-string "config edit")}))
        (println (messages/t :config/init-view-hint {:command (app-config/command-string "config list")}))
        (println)))))

(defn cmd-edit
  "Open config file in $EDITOR."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)
        editor (or (System/getenv "EDITOR") "vim")]
    ;; Create config if it doesn't exist
    (when-not (fs/exists? config-path)
      (write-config-file config-path default-config))

    (try
      (process/shell editor (str config-path))
      (print-success (messages/t :config/edit-saved {:path config-path}))
      (catch Exception e
        (print-error (messages/t :config/edit-failed {:message (.getMessage e)}))))))

(defn cmd-reset
  "Reset config to defaults."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)]
    (println)
    (print-info (messages/t :config/reset-warning))
    (print (messages/t :config/reset-prompt))
    (flush)
    (let [response (read-line)]
      (if (= (str/lower-case (str/trim response)) "y")
        (do
          (write-config-file config-path default-config)
          (println)
          (print-success (messages/t :config/reset-success))
          (println))
        (println (messages/t :config/reset-cancelled))))))

(defn cmd-backends
  "List available backends with status."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)
        config (load-merged-config config-path)]
    (backends/print-backends config)))

(defn cmd-backend
  "Set the LLM backend (shorthand for 'config set llm.backend')."
  [opts]
  (let [backend-str (:backend opts)
        config-path (or (:config opts) default-user-config-path)]
    (if-not backend-str
      (do
        (print-error (messages/t :config/backend-missing))
        (println (messages/t :config/backend-usage {:command (app-config/command-string "config backend <name>")}))
        (println)
        (println (messages/t :config/backend-available-header))
        (doseq [backend (keys backends/backend-specs)]
          (println (messages/t :config/backend-item {:name (name backend)})))
        (println)
        (println (messages/t :config/backend-detail-hint {:command (app-config/command-string "config backends")})))
      (let [backend-kw (keyword backend-str)
            validation (backends/validate-backend backend-kw)]
        (if (:valid? validation)
          ;; Backend is available, set it
          (let [user-config (or (read-config-file config-path) default-config)
                updated-config (assoc-in user-config [:llm :backend] backend-kw)]
            (write-config-file config-path updated-config)
            (println)
            (print-success (messages/t :config/backend-set-success {:backend backend-str}))
            (println (messages/t :config/backend-saved {:path config-path}))
            (let [info (backends/get-backend-info backend-kw)]
              (println (messages/t :config/backend-provider {:provider (:provider info)}))
              (when (:default-model info)
                (println (messages/t :config/backend-model {:model (:default-model info)}))))
            (println)
            (println (messages/t :config/backend-change-model))
            (println (messages/t :config/backend-change-model-hint {:command (app-config/command-string "config set llm.model <model-name>")}))
            (println))
          ;; Backend not available, show helpful error
          (backends/print-backend-error backend-kw))))))

(defn cmd-validate
  "Validate configuration file."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)]
    (println)
    (if-not (fs/exists? config-path)
      (do
        (print-error (messages/t :config/validate-no-file))
        (println (messages/t :config/validate-create-hint {:command (app-config/command-string "config init") :path config-path}))
        (println))
      (let [config (read-config-file config-path)]
        (if config
          (do
            (print-success (messages/t :config/validate-valid))
            (println (messages/t :config/validate-location {:path config-path}))
            (println)
            ;; Check backend validity
            (let [backend (get-in config [:llm :backend])
                  validation (when backend (backends/validate-backend backend))]
              (when validation
                (if (:valid? validation)
                  (println (messages/t :config/validate-backend-available
                                       {:backend (name backend)
                                        :status (style "Available" :green)}))
                  (println (messages/t :config/validate-backend-available
                                       {:backend (name backend)
                                        :status (style (:message validation) :yellow)}))))))
          (do
            (print-error (messages/t :config/validate-invalid))
            (println (messages/t :config/validate-location {:path config-path}))
            (println)
            (println (messages/t :config/validate-reset-hint {:command (app-config/command-string "config reset")}))))))))

;------------------------------------------------------------------------------ Compatibility functions for workflow-runner

(defn load-config
  "Load configuration with environment variable overrides.
   Compatible with old workflow-runner code."
  ([] (load-merged-config default-user-config-path))
  ([opts]
   (let [config-path (or (:config-file opts) default-user-config-path)]
     (load-merged-config config-path))))

(defn get-llm-backend
  "Get LLM backend from config, with workflow override support."
  [config workflow-override]
  (or workflow-override
      (get-in config [:llm :backend])
      :claude))

(defn get-llm-timeout
  "Get LLM timeout from config."
  [config]
  (get-in config [:llm :timeout-ms] 300000))

(defn get-llm-line-timeout
  "Get LLM line timeout from config."
  [config]
  (get-in config [:llm :line-timeout-ms] 60000))
