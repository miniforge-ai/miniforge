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

(defn home-dir []
  (str (fs/home) "/" (home-dir-name)))

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
