(ns ai.miniforge.cli.config
  "Enhanced configuration management for Miniforge CLI.

   Provides commands for viewing, editing, and managing configuration."
  (:require
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.cli.backends :as backends]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration paths and utilities

(def default-user-config-path
  (str (fs/home) "/.miniforge/config.edn"))

(def default-config
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
   :dashboard {:port 7878
               :auto-open false}})

(defn- style
  "Apply terminal styling using ANSI escape codes."
  [text color]
  (let [colors {:red "31" :green "32" :yellow "33" :cyan "36"}]
    (str "\033[" (get colors color "37") "m" text "\033[0m")))

(defn- print-success [msg]
  (println (style msg :green)))

(defn- print-error [msg]
  (println (style (str "Error: " msg) :red)))

(defn- print-info [msg]
  (println (style msg :cyan)))

;------------------------------------------------------------------------------ Layer 1
;; Config file operations

(defn- read-config-file
  "Read config file, returns nil if doesn't exist."
  [path]
  (when (fs/exists? path)
    (try
      (read-string (slurp path))
      (catch Exception e
        (println (style (str "Warning: Failed to read config file: " (.getMessage e)) :yellow))
        nil))))

(defn- write-config-file
  "Write config to file with pretty printing."
  [path config]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (pprint/pprint config))))

(defn- load-merged-config
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

(defn- format-config-value
  "Format a config value for display."
  [v]
  (cond
    (keyword? v) (name v)
    (string? v) v
    :else (pr-str v)))

(defn- parse-config-key
  "Parse config key path (e.g., 'llm.backend' -> [:llm :backend])."
  [key-str]
  (when key-str
    (mapv keyword (str/split key-str #"\."))))

(defn- parse-config-value
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
  (println (style "Current Configuration:" :cyan))
  (println)

  ;; LLM Settings
  (println "LLM Settings:")
  (println (str "  backend: " (format-config-value (get-in config [:llm :backend]))
               " (" (get-in backends/backend-specs [(get-in config [:llm :backend]) :provider] "Unknown") ")"))
  (println (str "  model: " (get-in config [:llm :model])))
  (println (str "  max-tokens: " (get-in config [:llm :max-tokens])))
  (println (str "  timeout: " (quot (get-in config [:llm :timeout-ms]) 60000) " minutes"))
  (println)

  ;; Workflow Settings
  (println "Workflow Settings:")
  (println (str "  max-iterations: " (get-in config [:workflow :max-iterations])))
  (println (str "  max-tokens: " (get-in config [:workflow :max-tokens])))
  (println (str "  failure-strategy: " (format-config-value (get-in config [:workflow :failure-strategy]))))
  (println)

  ;; Artifacts
  (println "Artifacts:")
  (println (str "  dir: " (get-in config [:artifacts :dir])))
  (println)

  ;; Config file location
  (println "Config Files:")
  (println (str "  User: " config-path
               (if (fs/exists? config-path)
                 (style " ✓" :green)
                 (style " (not created)" :yellow))))
  (println "  Defaults: <embedded>")
  (println)

  ;; Env var hints
  (println "Override with environment variables:")
  (println "  MINIFORGE_LLM_BACKEND=openai")
  (println "  MINIFORGE_LLM_MODEL=gpt-4")
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
        (print-error "Missing key argument")
        (println "Usage: miniforge config get <key>")
        (println "Example: miniforge config get llm.backend"))
      (let [config (load-merged-config config-path)
            key-path (parse-config-key key-str)
            value (get-in config key-path)]
        (if value
          (println (pr-str value))
          (print-error (str "Key not found: " key-str)))))))

(defn cmd-set
  "Set a configuration value."
  [opts]
  (let [key-str (:key opts)
        value-str (:value opts)
        config-path (or (:config opts) default-user-config-path)]
    (if (or (not key-str) (not value-str))
      (do
        (print-error "Missing arguments")
        (println "Usage: miniforge config set <key> <value>")
        (println "Example: miniforge config set llm.backend openai"))
      (let [user-config (or (read-config-file config-path) default-config)
            key-path (parse-config-key key-str)
            value (parse-config-value value-str)
            updated-config (assoc-in user-config key-path value)]
        (write-config-file config-path updated-config)
        (println)
        (print-success (str "✅ Set " key-str " to " (pr-str value)))
        (println (str "   Saved to: " config-path))
        (println)))))

(defn cmd-init
  "Initialize user config file."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)]
    (if (fs/exists? config-path)
      (do
        (print-info (str "Config already exists at " config-path))
        (println "Use 'miniforge config set <key> <value>' to modify"))
      (do
        (write-config-file config-path default-config)
        (println)
        (print-success (str "✅ Created config at " config-path))
        (println)
        (println "Edit with: miniforge config edit")
        (println "View with: miniforge config list")
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
      (print-success (str "Config saved: " config-path))
      (catch Exception e
        (print-error (str "Failed to open editor: " (.getMessage e)))))))

(defn cmd-reset
  "Reset config to defaults."
  [opts]
  (let [config-path (or (:config opts) default-user-config-path)]
    (println)
    (print-info "This will reset your config to defaults.")
    (print "Continue? (y/N): ")
    (flush)
    (let [response (read-line)]
      (if (= (str/lower-case (str/trim response)) "y")
        (do
          (write-config-file config-path default-config)
          (println)
          (print-success "✅ Config reset to defaults")
          (println))
        (println "Cancelled.")))))

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
        (print-error "Missing backend argument")
        (println "Usage: miniforge config backend <name>")
        (println)
        (println "Available backends:")
        (doseq [backend (keys backends/backend-specs)]
          (println (str "  " (name backend))))
        (println)
        (println "Run 'miniforge config backends' for detailed status."))
      (let [backend-kw (keyword backend-str)
            validation (backends/validate-backend backend-kw)]
        (if (:valid? validation)
          ;; Backend is available, set it
          (let [user-config (or (read-config-file config-path) default-config)
                updated-config (assoc-in user-config [:llm :backend] backend-kw)]
            (write-config-file config-path updated-config)
            (println)
            (print-success (str "✅ Backend set to " backend-str))
            (println (str "   Saved to: " config-path))
            (let [info (backends/get-backend-info backend-kw)]
              (println (str "   Provider: " (:provider info)))
              (when (:default-model info)
                (println (str "   Model: " (:default-model info) " (default)"))))
            (println)
            (println "To change model:")
            (println "  miniforge config set llm.model <model-name>")
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
        (print-error "Config file does not exist")
        (println (str "Run 'miniforge config init' to create: " config-path))
        (println))
      (let [config (read-config-file config-path)]
        (if config
          (do
            (print-success "✅ Config file is valid")
            (println (str "   Location: " config-path))
            (println)
            ;; Check backend validity
            (let [backend (get-in config [:llm :backend])
                  validation (when backend (backends/validate-backend backend))]
              (when validation
                (if (:valid? validation)
                  (println (str "   Backend " (name backend) ": " (style "Available" :green)))
                  (println (str "   Backend " (name backend) ": " (style (:message validation) :yellow)))))))
          (do
            (print-error "Config file is invalid or unreadable")
            (println (str "   Location: " config-path))
            (println)
            (println "Try resetting: miniforge config reset")))))))

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
