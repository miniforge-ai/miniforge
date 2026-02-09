(ns ai.miniforge.config.interface
  "Public interface for configuration management."
  (:require
   [ai.miniforge.config.user :as user]))

;; Re-export public functions
(def default-user-config-path user/default-user-config-path)
(def default-config user/default-config)

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
