(ns ai.miniforge.cli.main.commands.fleet
  "Fleet management commands."
  (:require
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Config helpers

(defn load-config
  "Load configuration from file, merging with defaults."
  [path default-config-path default-config]
  (let [config-path (or path default-config-path)]
    (if (fs/exists? config-path)
      (merge-with merge default-config (edn/read-string (slurp config-path)))
      default-config)))

(defn save-config
  "Save configuration to file."
  [config path default-config-path]
  (let [config-path (or path default-config-path)]
    (fs/create-dirs (fs/parent config-path))
    (spit config-path (pr-str config))))

;------------------------------------------------------------------------------ Layer 1
;; Fleet commands

(defn fleet-start-cmd
  [_opts]
  (display/print-info "Starting fleet daemon...")
  (println "TODO: Fleet daemon is an enterprise extension point (see miniforge-fleet)"))

(defn fleet-stop-cmd
  [_opts]
  (display/print-info "Stopping fleet daemon...")
  (println "TODO: Fleet daemon is an enterprise extension point (see miniforge-fleet)"))

(defn fleet-status-cmd
  [opts default-config-path default-config]
  (let [config (load-config (:config opts) default-config-path default-config)
        repos (get-in config [:fleet :repos] [])
        state-file (str (fs/home) "/.miniforge/state.edn")
        state (if (fs/exists? state-file)
                (edn/read-string (slurp state-file))
                {:workflows {:active 0 :pending 0 :completed 0 :failed 0}})]

    (println)
    (println (display/style "Fleet Status" :foreground :cyan :bold true))
    (println)
    (println (str "  Repositories: " (count repos)))
    (println (str "  Active Workflows: " (get-in state [:workflows :active] 0)))
    (println (str "  Pending Workflows: " (get-in state [:workflows :pending] 0)))
    (println (str "  Completed: " (get-in state [:workflows :completed] 0)))
    (println (str "  Failed: " (get-in state [:workflows :failed] 0)))
    (println)))

(defn fleet-add-cmd
  [opts default-config-path default-config]
  (let [{:keys [repo config]} opts]
    (if-not repo
      (display/print-error "Usage: miniforge fleet add <repo>")
      (let [cfg (load-config config default-config-path default-config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (conj repos repo))]
        (save-config new-cfg config default-config-path)
        (display/print-success (str "Added " repo " to fleet"))))))

(defn fleet-remove-cmd
  [opts default-config-path default-config]
  (let [{:keys [repo config]} opts]
    (if-not repo
      (display/print-error "Usage: miniforge fleet remove <repo>")
      (let [cfg (load-config config default-config-path default-config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (vec (remove #{repo} repos)))]
        (save-config new-cfg config default-config-path)
        (display/print-success (str "Removed " repo " from fleet"))))))
