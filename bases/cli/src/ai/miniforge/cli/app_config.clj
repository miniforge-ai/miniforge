(ns ai.miniforge.cli.app-config
  "Project-composed CLI app identity and filesystem layout."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def app-config-resource
  "Classpath resource path for CLI app identity."
  "config/cli/app.edn")

(def default-app-profile
  {:name "miniforge"
   :display-name "Miniforge"
   :description "AI-powered software development workflows"
   :system-check-title "Miniforge System Check"
   :home-dir-name ".miniforge"
   :tui-package "miniforge-tui"
   :help-examples ["doctor"
                   "run feature.spec.edn"
                   "web                        # Start web dashboard (port 7878)"
                   "web --port 3000 --open     # Custom port, auto-open browser"
                   "tui                        # Start terminal UI (requires miniforge-tui)"
                   "workflow list"
                   "workflow run :simple-v2"
                   "workflow run :financial-etl -i input.edn"
                   "workflow run :workflow-id --input-json '{\"task\": \"Prepare report\"}'"
                   "chain list"
                   "fleet add myorg/myrepo"
                   "pr review https://github.com/org/repo/pull/123"]})

(defn- read-app-config
  [resource]
  (let [config (-> resource slurp edn/read-string)]
    (or (:cli-app/profile config) {})))

(defn- resource-precedence
  [resource]
  (let [path (str resource)]
    (cond
      (str/includes? path "/bases/") 0
      (str/includes? path "/components/") 1
      (str/includes? path "/projects/") 2
      :else 3)))

(defn app-profile
  "Resolve the active CLI app profile from the classpath."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       app-config-resource))
       (sort-by resource-precedence)
       (map read-app-config)
       (reduce merge default-app-profile)))

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
