;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.cli.main
  "Miniforge CLI entry point.

   Provides commands for:
   - run      : Execute workflows from spec files
   - status   : Show workflow status
   - fleet    : Multi-repo management daemon
   - pr       : PR operations (list, review, respond, merge)
   - config   : Configuration management
   - doctor   : System health check
   - version  : Version information

   Note: This is designed to run in Babashka. Components that require
   JVM-only libraries (malli, etc.) are not directly required here.
   Instead, we use lightweight implementations suitable for CLI."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [bblgum.core :as gum]
   [ai.miniforge.cli.web :as web]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and pure helpers

(def version-info
  {:name "miniforge"
   :version "2026.01.20.1"
   :description "AI-powered software development workflows"})

(def default-config-path
  (str (fs/home) "/.miniforge/config.edn"))

(def default-config
  {:llm {:backend :claude-cli
         :model "claude-sonnet-4-20250514"}
   :fleet {:repos []
           :poll-interval-ms 60000
           :auto-review? false
           :auto-merge? false}
   :worktree {:base-path (str (fs/home) "/.miniforge/worktrees")}
   :logging {:level :info
             :output :human}})

(def ^:private ansi-colors
  {:red     "31"
   :green   "32"
   :yellow  "33"
   :blue    "34"
   :magenta "35"
   :cyan    "36"
   :white   "37"})

(defn- style
  "Apply terminal styling using ANSI escape codes."
  [text & {:keys [foreground bold]}]
  (let [codes (cond-> []
                bold (conj "1")
                foreground (conj (get ansi-colors foreground "37")))]
    (if (seq codes)
      (str "\033[" (str/join ";" codes) "m" text "\033[0m")
      text)))

(defn- print-error [msg]
  (println (style (str "Error: " msg) :foreground :red)))

(defn- print-success [msg]
  (println (style msg :foreground :green)))

(defn- print-info [msg]
  (println (style msg :foreground :cyan)))

;------------------------------------------------------------------------------ Layer 1
;; Configuration management

(defn- load-config
  "Load configuration from file, merging with defaults."
  [path]
  (let [config-path (or path default-config-path)]
    (if (fs/exists? config-path)
      (merge-with merge default-config (edn/read-string (slurp config-path)))
      default-config)))

(defn- save-config
  "Save configuration to file."
  [config path]
  (let [config-path (or path default-config-path)]
    (fs/create-dirs (fs/parent config-path))
    (spit config-path (pr-str config))))

;------------------------------------------------------------------------------ Layer 2
;; Command implementations

;; babashka.cli dispatch passes {:dispatch [...] :opts {...} :args [...]}
;; This helper extracts just the opts map for simpler command signatures.
(defn- get-opts
  "Extract opts from dispatch result."
  [m]
  (if (contains? m :opts)
    (:opts m)
    m))

;; ─────────────────────────────────────────────────────────────────────────────
;; Version command

(defn version-cmd
  "Show version information."
  [_m]
  (println (str (:name version-info) " " (:version version-info)))
  (println (:description version-info)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Doctor command

(defn- check-command
  "Check if a command is available."
  [cmd]
  (let [{:keys [exit]} (process/sh "which" cmd)]
    (zero? exit)))

(defn doctor-cmd
  "Check system health and dependencies."
  [_m]
  (println "\n" (style "Miniforge System Check" :foreground :cyan :bold true) "\n")

  (let [checks [["bb" "Babashka" "Required for CLI"]
                ["gum" "Charm Gum" "Required for TUI"]
                ["git" "Git" "Required for version control"]
                ["gh" "GitHub CLI" "Required for PR operations"]
                ["claude" "Claude CLI" "Required for LLM backend"]]]

    (doseq [[cmd name desc] checks]
      (let [available? (check-command cmd)
            status (if available?
                     (style "✓" :foreground :green)
                     (style "✗" :foreground :red))
            name-styled (if available? name (style name :foreground :red))]
        (println (format "  %s %-12s %s" status name-styled desc))))

    (println)

    ;; Check config
    (if (fs/exists? default-config-path)
      (println (style "✓" :foreground :green) "Config file exists at" default-config-path)
      (println (style "!" :foreground :yellow) "No config file. Run 'miniforge config init'"))

    (println)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Config commands

(defn config-init-cmd
  "Initialize configuration file."
  [m]
  (let [opts (get-opts m)
        config-path (or (:config opts) default-config-path)]
    (if (fs/exists? config-path)
      (do
        (print-info (str "Config already exists at " config-path))
        (println "Use 'miniforge config set <key> <value>' to modify"))
      (do
        (save-config default-config config-path)
        (print-success (str "Created config at " config-path))))))

(defn config-list-cmd
  "List all configuration values."
  [m]
  (let [opts (get-opts m)
        config (load-config (:config opts))]
    (println (pr-str config))))

(defn config-get-cmd
  "Get a configuration value."
  [m]
  (let [{:keys [key config]} (get-opts m)]
    (if-not key
      (print-error "Usage: miniforge config get <key>")
      (let [cfg (load-config config)
            path (map keyword (str/split key #"\."))
            value (get-in cfg path)]
        (if value
          (println (pr-str value))
          (print-error (str "Key not found: " key)))))))

(defn config-set-cmd
  "Set a configuration value."
  [m]
  (let [{:keys [key value config]} (get-opts m)]
    (if (or (not key) (not value))
      (print-error "Usage: miniforge config set <key> <value>")
      (let [cfg (load-config config)
            path (map keyword (str/split key #"\."))
            new-value (try (edn/read-string value) (catch Exception _ value))
            new-cfg (assoc-in cfg path new-value)]
        (save-config new-cfg config)
        (print-success (str "Set " key " = " (pr-str new-value)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Run command (stub - will integrate with orchestrator)

(defn run-cmd
  "Execute a workflow from a spec file."
  [m]
  (let [{:keys [spec interactive]} (get-opts m)]
    (cond
      interactive
      (do
        (print-info "Interactive mode not yet implemented")
        (println "Coming soon: conversational workflow execution"))

      (not spec)
      (print-error "Usage: miniforge run <spec-file> [--interactive]")

      (not (fs/exists? spec))
      (print-error (str "Spec file not found: " spec))

      :else
      (do
        (print-info (str "Running workflow from: " spec))
        (println "TODO: Integrate with orchestrator component")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Status command (stub)

(defn status-cmd
  "Show workflow status."
  [m]
  (let [{:keys [workflow-id]} (get-opts m)]
    (if workflow-id
      (do
        (print-info (str "Status for workflow: " workflow-id))
        (println "TODO: Query workflow component"))
      (do
        (print-info "All workflows:")
        (println "TODO: List active workflows")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Fleet commands

(defn fleet-start-cmd
  "Start the fleet daemon."
  [_m]
  (print-info "Starting fleet daemon...")
  (println "TODO: Implement fleet daemon"))

(defn fleet-stop-cmd
  "Stop the fleet daemon."
  [_m]
  (print-info "Stopping fleet daemon...")
  (println "TODO: Implement fleet daemon"))

(defn fleet-status-cmd
  "Show fleet status summary."
  [m]
  (let [opts (get-opts m)
        config (load-config (:config opts))
        repos (get-in config [:fleet :repos] [])
        state-file (str (fs/home) "/.miniforge/state.edn")
        state (if (fs/exists? state-file)
                (edn/read-string (slurp state-file))
                {:workflows {:active 0 :pending 0 :completed 0 :failed 0}})]

    (println)
    (println (style "Fleet Status" :foreground :cyan :bold true))
    (println)
    (println (str "  Repositories: " (count repos)))
    (println (str "  Active Workflows: " (get-in state [:workflows :active] 0)))
    (println (str "  Pending Workflows: " (get-in state [:workflows :pending] 0)))
    (println (str "  Completed: " (get-in state [:workflows :completed] 0)))
    (println (str "  Failed: " (get-in state [:workflows :failed] 0)))
    (println)))

(defn- draw-box
  "Draw a styled box using gum."
  [title content]
  (let [full-content (str title "\n\n" content)
        result (gum/gum :style [full-content]
                        :border "rounded"
                        :border-foreground "cyan"
                        :padding "0 1")]
    (str/join "\n" (:result result))))

(defn- clear-screen
  "Clear terminal screen."
  []
  (print "\033[2J\033[H")
  (flush))

(defn- gum-choose
  "Present a choice menu and return selected item."
  [items & {:keys [header cursor]}]
  (let [result (gum/gum :choose items
                        :header (or header "Select an option:")
                        :cursor (or cursor "▸ ")
                        :cursor-prefix "● "
                        :selected-prefix "● "
                        :unselected-prefix "○ ")]
    (first (:result result))))

(defn- gum-confirm
  "Ask for confirmation."
  [prompt]
  (let [result (gum/gum :confirm [prompt])]
    (zero? (:status result))))

(defn- gum-input
  "Get text input from user."
  [& {:keys [placeholder header]}]
  (let [result (gum/gum :input []
                        :placeholder (or placeholder "")
                        :header (or header ""))]
    (first (:result result))))

(defn- fetch-prs-for-repo
  "Fetch open PRs for a repository."
  [repo]
  (let [result (process/sh "gh" "pr" "list" "--repo" repo
                           "--json" "number,title,state,author,createdAt,url"
                           "--limit" "20")]
    (if (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception _ []))
      [])))

(defn- display-status-panel
  "Display the status panel with workflow counts."
  [state repos]
  (let [wf-content (str "Active:    " (style (str (get-in state [:workflows :active] 0)) :foreground :green) "\n"
                        "Pending:   " (style (str (get-in state [:workflows :pending] 0)) :foreground :yellow) "\n"
                        "Completed: " (style (str (get-in state [:workflows :completed] 0)) :foreground :green) "\n"
                        "Failed:    " (style (str (get-in state [:workflows :failed] 0)) :foreground :red) "\n"
                        "\nRepositories: " (count repos))]
    (println (draw-box "FLEET STATUS" wf-content))
    (println)))

(defn- dashboard-view-repos
  "View and manage fleet repositories."
  [config config-path]
  (let [repos (get-in config [:fleet :repos] [])]
    (if (empty? repos)
      (do
        (println (style "\nNo repositories configured." :foreground :yellow))
        (when (gum-confirm "Add a repository now?")
          (let [repo (gum-input :placeholder "owner/repo" :header "Enter repository:")]
            (when (and repo (not (str/blank? repo)))
              (let [new-cfg (update-in config [:fleet :repos] (fnil conj []) repo)]
                (save-config new-cfg config-path)
                (print-success (str "Added " repo))))))
        config)
      (let [choices (concat (map #(str "📦 " %) repos)
                            ["" "➕ Add repository" "➖ Remove repository" "← Back"])
            selection (gum-choose choices :header "Fleet Repositories")]
        (cond
          (or (nil? selection) (= selection "← Back"))
          config

          (= selection "➕ Add repository")
          (let [repo (gum-input :placeholder "owner/repo" :header "Enter repository:")]
            (if (and repo (not (str/blank? repo)))
              (let [new-cfg (update-in config [:fleet :repos] (fnil conj []) repo)]
                (save-config new-cfg config-path)
                (print-success (str "Added " repo))
                new-cfg)
              config))

          (= selection "➖ Remove repository")
          (let [repo-choice (gum-choose repos :header "Select repository to remove:")]
            (if repo-choice
              (let [new-cfg (update-in config [:fleet :repos] #(vec (remove #{repo-choice} %)))]
                (save-config new-cfg config-path)
                (print-success (str "Removed " repo-choice))
                new-cfg)
              config))

          (str/starts-with? selection "📦 ")
          (let [repo (subs selection 3)]
            (println (style (str "\nRepository: " repo) :foreground :cyan :bold true))
            (println "Fetching PRs...")
            (let [prs (fetch-prs-for-repo repo)]
              (if (empty? prs)
                (println "  No open PRs")
                (do
                  (println (str "  " (count prs) " open PR(s):"))
                  (doseq [{:keys [number title author]} prs]
                    (println (str "  #" number " " title " (" (:login author) ")"))))))
            (println)
            (gum-input :placeholder "Press Enter to continue...")
            config)

          :else config)))))

(defn- dashboard-view-prs
  "View all PRs across fleet repositories."
  [config]
  (let [repos (get-in config [:fleet :repos] [])]
    (if (empty? repos)
      (println (style "\nNo repositories configured. Add some first." :foreground :yellow))
      (do
        (println (style "\nFetching PRs across fleet..." :foreground :cyan))
        (let [all-prs (mapcat (fn [repo]
                                (map #(assoc % :repo repo) (fetch-prs-for-repo repo)))
                              repos)]
          (if (empty? all-prs)
            (println "  No open PRs across fleet")
            (let [pr-choices (concat
                               (map (fn [{:keys [repo number title]}]
                                      (str "#" number " [" repo "] " (subs title 0 (min 50 (count title)))))
                                    all-prs)
                               ["" "← Back"])
                  selection (gum-choose pr-choices :header (str (count all-prs) " Open PRs"))]
              (when (and selection (not= selection "← Back") (not (str/blank? selection)))
                (let [pr-num (-> selection (str/split #" ") first (subs 1) Integer/parseInt)
                      pr-data (first (filter #(= (:number %) pr-num) all-prs))]
                  (when pr-data
                    (println)
                    (println (draw-box (str "PR #" (:number pr-data))
                                       (str "Repository: " (:repo pr-data) "\n"
                                            "Title: " (:title pr-data) "\n"
                                            "Author: " (get-in pr-data [:author :login]) "\n"
                                            "State: " (:state pr-data) "\n"
                                            "URL: " (:url pr-data))))
                    (let [action (gum-choose ["Review PR" "Open in browser" "← Back"]
                                             :header "Action:")]
                      (case action
                        "Review PR" (do
                                      (print-info "Starting PR review...")
                                      (println "TODO: Implement agent-based PR review"))
                        "Open in browser" (process/sh "open" (:url pr-data))
                        nil))))))))))))

(defn- dashboard-quick-actions
  "Show quick actions menu."
  [config]
  (let [action (gum-choose ["🔍 Review next PR"
                            "🚀 Start new workflow"
                            "📊 Refresh status"
                            "⚙️  Configure settings"
                            "← Back"]
                           :header "Quick Actions")]
    (case action
      "🔍 Review next PR"
      (let [repos (get-in config [:fleet :repos] [])
            all-prs (mapcat (fn [repo]
                              (map #(assoc % :repo repo) (fetch-prs-for-repo repo)))
                            repos)]
        (if (empty? all-prs)
          (println (style "\nNo PRs to review." :foreground :yellow))
          (let [pr (first all-prs)]
            (println)
            (println (style (str "Next PR: #" (:number pr) " in " (:repo pr)) :foreground :cyan))
            (println (:title pr))
            (when (gum-confirm "Start review?")
              (print-info "Starting PR review...")
              (println "TODO: Implement agent-based PR review")))))

      "🚀 Start new workflow"
      (let [spec-file (gum-input :placeholder "path/to/spec.edn" :header "Workflow spec file:")]
        (when (and spec-file (not (str/blank? spec-file)))
          (if (fs/exists? spec-file)
            (do
              (print-info (str "Starting workflow from: " spec-file))
              (println "TODO: Integrate with orchestrator"))
            (print-error (str "File not found: " spec-file)))))

      "📊 Refresh status"
      (println (style "\nStatus refreshed." :foreground :green))

      "⚙️  Configure settings"
      (let [setting (gum-choose ["Auto-review PRs"
                                 "Auto-merge approved PRs"
                                 "Poll interval"
                                 "← Back"]
                                :header "Settings")]
        (case setting
          "Auto-review PRs"
          (let [current (get-in config [:fleet :auto-review?] false)]
            (println (str "Current: " (if current "enabled" "disabled")))
            (when (gum-confirm (str (if current "Disable" "Enable") " auto-review?"))
              (println "TODO: Update config")))

          "Auto-merge approved PRs"
          (let [current (get-in config [:fleet :auto-merge?] false)]
            (println (str "Current: " (if current "enabled" "disabled")))
            (when (gum-confirm (str (if current "Disable" "Enable") " auto-merge?"))
              (println "TODO: Update config")))

          "Poll interval"
          (println (str "Current: " (get-in config [:fleet :poll-interval-ms] 60000) "ms"))

          nil))

      nil)))

;

(defn fleet-dashboard-cmd
  "Open interactive two-pane TUI dashboard."
  [m]
  (print-info "TUI dashboard is deprecated. Use the web dashboard instead:")
  (println "  bb miniforge fleet web")
  (println)
  (println "The web dashboard offers:")
  (println "  - Better UX with htmx-powered interactions")
  (println "  - AI chat integration")
  (println "  - PR risk analysis and batch operations")
  (println "  - Accessible from any browser")
  nil)

(defn fleet-web-cmd
  "Start web-based fleet dashboard."
  [m]
  (let [{:keys [port]} (get-opts m)
        port (or port 8787)]
    (print-info (str "Starting web dashboard on port " port "..."))
    (web/start-server! :port port)
    ;; Keep running until interrupted
    @(promise)))

(defn fleet-add-cmd
  "Add a repository to the fleet."
  [m]
  (let [{:keys [repo config]} (get-opts m)]
    (if-not repo
      (print-error "Usage: miniforge fleet add <repo>")
      (let [cfg (load-config config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (conj repos repo))]
        (save-config new-cfg config)
        (print-success (str "Added " repo " to fleet"))))))

(defn fleet-remove-cmd
  "Remove a repository from the fleet."
  [m]
  (let [{:keys [repo config]} (get-opts m)]
    (if-not repo
      (print-error "Usage: miniforge fleet remove <repo>")
      (let [cfg (load-config config)
            repos (get-in cfg [:fleet :repos] [])
            new-cfg (assoc-in cfg [:fleet :repos] (vec (remove #{repo} repos)))]
        (save-config new-cfg config)
        (print-success (str "Removed " repo " from fleet"))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; PR commands

(defn pr-list-cmd
  "List PRs using GitHub CLI."
  [m]
  (let [{:keys [repo config]} (get-opts m)
        cfg (load-config config)
        repos (if repo [repo] (get-in cfg [:fleet :repos] []))]

    (if (empty? repos)
      (do
        (print-error "No repositories specified.")
        (println "Use --repo <owner/repo> or add repos with 'miniforge fleet add'"))
      (doseq [r repos]
        (println)
        (println (style (str "PRs for " r) :foreground :cyan :bold true))
        (let [result (process/sh "gh" "pr" "list" "--repo" r "--json" "number,title,state,author,createdAt" "--limit" "10")]
          (if (zero? (:exit result))
            (try
              (let [prs (json/parse-string (:out result) true)]
                (if (empty? prs)
                  (println "  No open PRs")
                  (doseq [{:keys [number title state author]} prs]
                    (let [status-style (case state
                                         "OPEN" :green
                                         "MERGED" :magenta
                                         "CLOSED" :red
                                         :white)]
                      (println (str "  #" number " "
                                    (style (str "[" state "]") :foreground status-style)
                                    " " title
                                    " (" (:login author "unknown") ")"))))))
              (catch Exception _
                ;; Fallback to simple text output if JSON parsing fails
                (let [result2 (process/sh "gh" "pr" "list" "--repo" r "--limit" "10")]
                  (if (zero? (:exit result2))
                    (println (:out result2))
                    (print-error (str "Failed to list PRs: " (:err result2)))))))
            (print-error (str "Failed to query GitHub: " (:err result)))))))))

(defn pr-review-cmd
  "Review a PR."
  [m]
  (let [{:keys [url]} (get-opts m)]
    (if-not url
      (print-error "Usage: miniforge pr review <pr-url>")
      (do
        (print-info (str "Reviewing: " url))
        (println "TODO: Implement PR review with agent")))))

(defn pr-respond-cmd
  "Respond to PR comments."
  [m]
  (let [{:keys [url]} (get-opts m)]
    (if-not url
      (print-error "Usage: miniforge pr respond <pr-url>")
      (do
        (print-info (str "Responding to comments on: " url))
        (println "TODO: Implement PR comment response")))))

(defn pr-merge-cmd
  "Merge a PR."
  [m]
  (let [{:keys [url]} (get-opts m)]
    (if-not url
      (print-error "Usage: miniforge pr merge <pr-url>")
      (do
        (print-info (str "Merging: " url))
        (println "TODO: Use gh pr merge")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Help command

(defn help-cmd
  "Show help."
  [_m]
  (println "
miniforge - AI-powered software development workflows

Usage: miniforge <command> [options]

Commands:
  run <spec-file>     Execute a workflow from a spec file
    --interactive     Interactive mode (chat-based)
    --worktree <path> Custom worktree path

  status [id]         Show workflow status

  fleet <subcommand>  Multi-repo management
    start             Start fleet daemon
    stop              Stop fleet daemon
    status            Show fleet status
    dashboard         Open TUI dashboard (deprecated - use web)
    web [--port N]    Start web dashboard (default port: 8787)
    add <repo>        Add repo to fleet
    remove <repo>     Remove repo from fleet

  pr <subcommand>     PR operations
    list              List PRs
    review <url>      Review a PR
    respond <url>     Respond to comments
    merge <url>       Merge a PR

  config <subcommand> Configuration
    init              Initialize config file
    list              List all config
    get <key>         Get config value
    set <key> <val>   Set config value

  doctor              Check system health
  version             Show version info
  help                Show this help

Examples:
  miniforge doctor
  miniforge run feature.spec.edn
  miniforge fleet add myorg/myrepo
  miniforge fleet web                  # Start web dashboard
  miniforge pr review https://github.com/org/repo/pull/123
"))

;------------------------------------------------------------------------------ Layer 3
;; CLI dispatch

(def dispatch-table
  [{:cmds ["version"] :fn version-cmd}
   {:cmds ["doctor"]  :fn doctor-cmd}
   {:cmds ["help"]    :fn help-cmd}
   {:cmds []          :fn help-cmd}  ; default

   ;; Run command
   {:cmds ["run"]
    :fn run-cmd
    :args->opts [:spec]
    :spec {:interactive {:coerce :boolean :alias :i}
           :worktree    {:alias :w}}}

   ;; Status command
   {:cmds ["status"]
    :fn status-cmd
    :args->opts [:workflow-id]}

   ;; Config subcommands
   {:cmds ["config" "init"] :fn config-init-cmd}
   {:cmds ["config" "list"] :fn config-list-cmd}
   {:cmds ["config" "get"]  :fn config-get-cmd  :args->opts [:key]}
   {:cmds ["config" "set"]  :fn config-set-cmd  :args->opts [:key :value]}

   ;; Fleet subcommands
   {:cmds ["fleet" "start"]     :fn fleet-start-cmd}
   {:cmds ["fleet" "stop"]      :fn fleet-stop-cmd}
   {:cmds ["fleet" "status"]    :fn fleet-status-cmd}
   {:cmds ["fleet" "dashboard"] :fn fleet-dashboard-cmd}
   {:cmds ["fleet" "web"]       :fn fleet-web-cmd
    :spec {:port {:coerce :int :alias :p :default 8787}}}
   {:cmds ["fleet" "add"]       :fn fleet-add-cmd    :args->opts [:repo]}
   {:cmds ["fleet" "remove"]    :fn fleet-remove-cmd :args->opts [:repo]}

   ;; PR subcommands
   {:cmds ["pr" "list"]    :fn pr-list-cmd    :spec {:repo {:alias :r}}}
   {:cmds ["pr" "review"]  :fn pr-review-cmd  :args->opts [:url]}
   {:cmds ["pr" "respond"] :fn pr-respond-cmd :args->opts [:url]}
   {:cmds ["pr" "merge"]   :fn pr-merge-cmd   :args->opts [:url]}])

(defn -main
  "CLI entry point."
  [& args]
  (cli/dispatch dispatch-table args))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test CLI dispatch
  (-main "help")
  (-main "version")
  (-main "doctor")
  (-main "config" "init")
  (-main "config" "list")
  (-main "run" "test.spec.edn")
  (-main "fleet" "dashboard")

  :end)
