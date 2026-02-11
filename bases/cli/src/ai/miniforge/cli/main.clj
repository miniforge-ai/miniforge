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
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.observability :as observability]))

;; TUI components loaded conditionally (only in JVM/jlink bundled runtime)
;; Not available in Babashka - use 'miniforge-tui' package for terminal UI
(def tui-available?
  (try
    (require '[ai.miniforge.event-stream.interface :as es])
    (require '[ai.miniforge.tui-views.interface :as tui])
    true
    (catch Exception _ false)))

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

(defn- print-agent-backend-error-header
  "Print header for agent backend errors."
  [completed-work]
  (println (style "⚠️  Agent System Error (Not Your Fault!)" :foreground :yellow :bold true))
  (when (seq completed-work)
    (println)
    (println (style "Your task completed successfully:" :foreground :green))
    (doseq [work completed-work]
      (println (str "  " (style "✅" :foreground :green) " " work)))))

(defn- print-task-code-error-header
  "Print header for task code errors."
  []
  (println (style "❌ Task Code Error" :foreground :red :bold true)))

(defn- print-external-error-header
  "Print header for external service errors."
  []
  (println (style "⚠️  External Service Error" :foreground :yellow :bold true)))

(defn- print-generic-error-header
  "Print header for unclassified errors."
  []
  (println (style "❌ Error" :foreground :red :bold true)))

(defn- print-agent-backend-error-context
  "Print context for agent backend errors."
  [completed-work]
  (if (seq completed-work)
    (println "This is a bug in Claude Code's agent runtime, not in your task or miniforge.\nYour work is complete and safe.")
    (println "This is a bug in Claude Code's agent runtime.")))

(defn- print-task-code-error-context
  "Print context for task code errors."
  [completed-work]
  (println "This is an issue with the code being generated or the task specification.")
  (when (seq completed-work)
    (println)
    (println "Partial work completed:")
    (doseq [work completed-work]
      (println (str "  ⏸️  " work)))))

(defn- print-external-error-context
  "Print context for external service errors."
  [completed-work]
  (println "This is not an issue with your code or miniforge. The external service\nis temporarily unavailable.")
  (when (seq completed-work)
    (println)
    (println "Partial work completed:")
    (doseq [work completed-work]
      (println (str "  " (style "✅" :foreground :green) " " work)))))

(defn- print-error-header
  "Print error header based on error type."
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-header completed-work)
    :task-code (print-task-code-error-header)
    :external (print-external-error-header)
    (print-generic-error-header)))

(defn- print-error-context
  "Print error context based on error type."
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-context completed-work)
    :task-code (print-task-code-error-context completed-work)
    :external (print-external-error-context completed-work)
    nil))

(defn- print-error-report-url
  "Print error reporting URL if available."
  [report-url vendor]
  (when report-url
    (println)
    (println (str (style "📝 Please report this to " :foreground :cyan) vendor ":"))
    (println (str "   " report-url))))

(defn- get-retry-recommendation
  "Get retry recommendation message based on error type."
  [error-type]
  (case error-type
    :task-code "Fix the issue and retry the task."
    :external "Wait a few minutes and retry - this is likely transient."
    :agent-backend "Try again later after the bug is fixed."
    "Check the error and decide if retry is appropriate."))

(defn- print-retry-recommendation
  "Print retry recommendation based on error characteristics."
  [should-retry error-type completed-work]
  (println)
  (if should-retry
    (println (str (style "🔄 Recommendation: " :foreground :cyan)
                 (get-retry-recommendation error-type)))
    (println (str (style "⚙️  " :foreground :cyan)
                 (if (seq completed-work)
                   "No need to retry - your task succeeded."
                   "Report this bug and try again later.")))))

(defn- print-classified-error
  "Display a classified error with rich formatting.
   Expects error-classification map from error-classifier/classify-error."
  [error-classification]
  (when error-classification
    (let [{:keys [type message completed-work report-url should-retry vendor]} error-classification]
      (print-error-header type completed-work)
      (println)
      (println (str "  " message))
      (println)
      (print-error-context type completed-work)
      (print-error-report-url report-url vendor)
      (print-retry-recommendation should-retry type completed-work))))

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
  (config/cmd-init (get-opts m)))

(defn config-list-cmd
  "List all configuration values."
  [m]
  (config/cmd-list (get-opts m)))

(defn config-get-cmd
  "Get a configuration value."
  [m]
  (config/cmd-get (get-opts m)))

(defn config-set-cmd
  "Set a configuration value."
  [m]
  (config/cmd-set (get-opts m)))

(defn config-edit-cmd
  "Edit configuration file."
  [m]
  (config/cmd-edit (get-opts m)))

(defn config-reset-cmd
  "Reset configuration to defaults."
  [m]
  (config/cmd-reset (get-opts m)))

(defn config-backends-cmd
  "List available backends."
  [m]
  (config/cmd-backends (get-opts m)))

(defn config-backend-cmd
  "Set LLM backend."
  [m]
  (config/cmd-backend (get-opts m)))

(defn config-validate-cmd
  "Validate configuration."
  [m]
  (config/cmd-validate (get-opts m)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Run command - Execute workflow from spec file

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
      (try
        (print-info (str "Parsing workflow spec: " spec))
        (let [parsed-spec (spec-parser/parse-spec-file spec)
              validation (spec-parser/validate-spec parsed-spec)]

          (if-not (:valid? validation)
            (do
              (print-error "Invalid workflow spec:")
              (doseq [error (:errors validation)]
                (println (str "  - " error))))

            (do
              (print-info (str "Running workflow: " (:spec/title parsed-spec)))
              (workflow-runner/run-workflow-from-spec!
               parsed-spec
               {:output :pretty
                :quiet false}))))
        (catch Exception e
          ;; Try to classify the error if agent-runtime is available
          (let [error-classification (try
                                      (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                        (when classifier
                                          (classifier e (ex-data e))))
                                      (catch Exception _ nil))]
            (if error-classification
              (print-classified-error error-classification)
              ;; Fallback to basic error display
              (do
                (print-error (str "Failed to run workflow: " (ex-message e)))
                (when-let [data (ex-data e)]
                  (println (str "  Details: " (pr-str data))))))))))))

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
  (println "TODO: Fleet daemon is an enterprise extension point (see miniforge-fleet)"))

(defn fleet-stop-cmd
  "Stop the fleet daemon."
  [_m]
  (print-info "Stopping fleet daemon...")
  (println "TODO: Fleet daemon is an enterprise extension point (see miniforge-fleet)"))

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

;; ─────────────────────────────────────────────────────────────────────────────
;; Monitoring commands (web dashboard and TUI)

(defn web-cmd
  "Start web dashboard for workflow monitoring.

   Launches a web server with real-time workflow visualization.
   Provides 5 N5 views: workflow list, detail, evidence, artifacts, DAG kanban."
  [m]
  (let [{:keys [port open]} (get-opts m)
        port (or port
                 (get-in (config/load-config) [:dashboard :port])
                 7878)]
    (try
      ;; Conditionally require web-dashboard (may not be on classpath in Babashka)
      (require '[ai.miniforge.web-dashboard.interface :as dashboard])
      (require '[ai.miniforge.event-stream.interface :as es])

      (let [dashboard-ns (find-ns 'ai.miniforge.web-dashboard.interface)
            es-ns (find-ns 'ai.miniforge.event-stream.interface)]
        (when-not (and dashboard-ns es-ns)
          (print-error "Web dashboard not available in this runtime.")
          (println "The dashboard requires JVM components not available in Babashka.")
          (System/exit 1))

        (let [start! (ns-resolve dashboard-ns 'start!)
              create-stream (ns-resolve es-ns 'create-event-stream)
              event-stream (create-stream)

              ;; Create PR train manager
              pr-train-manager (try
                                 (require '[ai.miniforge.pr-train.interface :as pr-train])
                                 (when-let [pr-ns (find-ns 'ai.miniforge.pr-train.interface)]
                                   (when-let [create-fn (ns-resolve pr-ns 'create-manager)]
                                     (create-fn)))
                                 (catch Exception e
                                   (println "Warning: Could not create PR train manager:" (.getMessage e))
                                   nil))

              ;; Create repo DAG manager
              repo-dag-manager (try
                                 (require '[ai.miniforge.repo-dag.interface :as repo-dag])
                                 (when-let [dag-ns (find-ns 'ai.miniforge.repo-dag.interface)]
                                   (when-let [create-fn (ns-resolve dag-ns 'create-manager)]
                                     (create-fn)))
                                 (catch Exception e
                                   (println "Warning: Could not create repo DAG manager:" (.getMessage e))
                                   nil))

              url (str "http://localhost:" port)]
          (print-info (str "Starting web dashboard on port " port "..."))
          (start! {:port port
                   :event-stream event-stream
                   :pr-train-manager pr-train-manager
                   :repo-dag-manager repo-dag-manager})
          (println)
          (println (str "  " url))
          (println)
          (if open
            (do
              (println "Opening browser...")
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              (println "Press Ctrl+C to stop")
              @(promise))
            (do
              (println "Press Enter to open in browser, Ctrl+C to stop")
              (read-line)
              (println "Opening browser...")
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              @(promise)))))  ; Block until interrupted
      (catch java.net.BindException _e
        (print-error (str "Port " port " is already in use."))
        (println)
        (println "Solutions:")
        (println (str "  1. Use a different port: bb miniforge web --port " (inc port)))
        (println (str "  2. Kill the process using port " port ":"))
        (println (str "     lsof -ti:" port " | xargs kill"))
        (System/exit 1))
      (catch Exception e
        (print-error (str "Failed to start web dashboard: " (ex-message e)))
        (System/exit 1)))))

(defn tui-cmd
  "Start terminal UI for workflow monitoring.

   The TUI provides real-time visibility into workflows:
   - Workflow list (status, phase, progress)
   - Workflow detail (phase list, agent output)
   - Evidence browser (intent, phases, validation)
   - Artifact browser (diffs, logs, test reports)
   - DAG kanban (task pipeline visualization)

   Navigation:
   - j/k: navigate up/down
   - Enter: drill into detail
   - Esc: go back
   - 1-5: switch views directly
   - /: search mode
   - :: command mode
   - q: quit

   Note: The TUI requires the JVM runtime. Install via Homebrew:
     brew install miniforge-tui

   The main 'miniforge' CLI (this one) uses Babashka for speed.
   The 'miniforge-tui' package includes a jlink-bundled JVM runtime."
  [_m]
  (if-not tui-available?
    (do
      (print-error "TUI not available in this runtime.")
      (println)
      (println "The TUI requires JVM/Lanterna which isn't available in Babashka.")
      (println)
      (println "To use the terminal UI, install the separate package:")
      (println "  brew install miniforge-tui")
      (println)
      (println "Or use the web dashboard instead:")
      (println "  miniforge web")
      (System/exit 1))
    (do
      (print-info "Starting TUI dashboard...")
      (print-info "Press 'q' to quit, '?' for help")
      (println)
      (try
        (let [es (requiring-resolve 'ai.miniforge.event-stream.interface)
              tui (requiring-resolve 'ai.miniforge.tui-views.interface)
              create-stream (ns-resolve es 'create-event-stream)
              start-tui! (ns-resolve tui 'start-tui!)
              event-stream (create-stream)]
          ;; Start TUI (blocks until quit)
          (start-tui! event-stream))
        (catch Exception e
          (print-error (str "Failed to start TUI: " (.getMessage e)))
          (when (str/includes? (str e) "terminal")
            (println)
            (println "Note: The TUI requires a proper terminal environment.")
            (println "Try running from Terminal.app or iTerm2, not from an IDE.")))))))

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
;; Workflow commands

(defn workflow-run-cmd
  "Execute a workflow by ID."
  [m]
  (let [{:keys [workflow-id version input input-json output quiet]} (get-opts m)]
    (if-not workflow-id
      (print-error "Usage: miniforge workflow run <workflow-id> [options]")
      (try
        (workflow-runner/run-workflow!
         (keyword (str/replace workflow-id #"^:" ""))
         {:version (or version "latest")
          :input input
          :input-json input-json
          :output (or output :pretty)
          :quiet (boolean quiet)})
        (catch Exception e
          (print-error (str "Workflow execution failed: " (ex-message e))))))))

(defn workflow-list-cmd
  "List available workflows."
  [_m]
  (workflow-runner/list-workflows!))

;; ─────────────────────────────────────────────────────────────────────────────
;; Observability commands

(defn logs-tail-cmd
  "Tail logs for a workflow."
  [m]
  (let [opts (get-opts m)]
    (observability/handle-logs (assoc opts :subcommand "tail"))))

(defn logs-list-cmd
  "List available log files."
  [_m]
  (observability/handle-logs {:subcommand "list"}))

(defn events-tail-cmd
  "Tail events for a workflow."
  [m]
  (let [opts (get-opts m)]
    (observability/handle-events (assoc opts :subcommand "tail"))))

(defn events-list-cmd
  "List available event stream files."
  [_m]
  (observability/handle-events {:subcommand "list"}))

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

  dashboard           Start workflow monitoring dashboard (default port: 8080)
    --port N          Port number
    --open            Open browser automatically

  workflow <subcommand>  Workflow execution
    run <id>          Execute a workflow by ID
      -v, --version   Workflow version (default: latest)
      -i, --input     Input file (EDN or JSON)
      --input-json    Inline JSON input
      -o, --output    Output format: pretty, json, edn (default: pretty)
      -q, --quiet     Suppress progress output
    list              List available workflows

  fleet <subcommand>  Multi-repo management
    start             Start fleet daemon
    stop              Stop fleet daemon
    status            Show fleet status
    dashboard         Open TUI dashboard (deprecated - use tui or web)
    tui               Terminal UI (5 N5 views, requires miniforge-tui package)
    web [--port N]    Start web dashboard (default port: 8787)
    add <repo>        Add repo to fleet
    remove <repo>     Remove repo from fleet

  pr <subcommand>     PR operations
    list              List PRs
    review <url>      Review a PR
    respond <url>     Respond to comments
    merge <url>       Merge a PR

  config <subcommand> Configuration management
    init              Initialize config file
    list              List all configuration values
    get <key>         Get specific config value
    set <key> <val>   Set config value
    edit              Open config in $EDITOR
    reset             Reset config to defaults
    backends          List available LLM backends with status
    backend <name>    Set LLM backend (shorthand)
    validate          Validate config file

  doctor              Check system health
  version             Show version info
  help                Show this help

Note:
  miniforge (this CLI) uses Babashka for fast startup.
  For terminal UI, install: brew install miniforge-tui (includes jlink-bundled JVM)

Examples:
  miniforge doctor
  miniforge run feature.spec.edn
  miniforge web                        # Start web dashboard (port 8080)
  miniforge web --port 3000 --open     # Custom port, auto-open browser
  miniforge tui                        # Start terminal UI (requires miniforge-tui)
  miniforge workflow list
  miniforge workflow run :simple-v2
  miniforge workflow run :canonical-sdlc-v1 -i input.edn
  miniforge workflow run :workflow-id --input-json '{\"task\": \"Build feature\"}'
  miniforge fleet add myorg/myrepo
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

   ;; Monitoring commands
   {:cmds ["web"]
    :fn web-cmd
    :spec {:port {:coerce :int :alias :p :default 8080}
           :open {:coerce :boolean :alias :o}}}

   {:cmds ["tui"]
    :fn tui-cmd}

   ;; Workflow commands
   {:cmds ["workflow" "run"]
    :fn workflow-run-cmd
    :args->opts [:workflow-id]
    :spec {:version {:coerce :string :alias :v :default "latest"}
           :input {:alias :i}
           :input-json {}
           :output {:coerce :keyword :alias :o :default :pretty}
           :quiet {:coerce :boolean :alias :q}
           :dashboard-url {:coerce :string :alias :d}}}

   {:cmds ["workflow" "list"]
    :fn workflow-list-cmd}

   ;; Config subcommands
   {:cmds ["config"] :fn help-cmd}  ; Show help when no subcommand provided
   {:cmds ["config" "init"] :fn config-init-cmd}
   {:cmds ["config" "list"] :fn config-list-cmd}
   {:cmds ["config" "get"]  :fn config-get-cmd  :args->opts [:key]}
   {:cmds ["config" "set"]  :fn config-set-cmd  :args->opts [:key :value]}
   {:cmds ["config" "edit"] :fn config-edit-cmd}
   {:cmds ["config" "reset"] :fn config-reset-cmd}
   {:cmds ["config" "backends"] :fn config-backends-cmd}
   {:cmds ["config" "backend"] :fn config-backend-cmd :args->opts [:backend]}
   {:cmds ["config" "validate"] :fn config-validate-cmd}

   ;; Observability commands
   {:cmds ["logs" "tail"]
    :fn logs-tail-cmd
    :args->opts [:workflow-id]
    :spec {:file {:alias :f}
           :lines {:coerce :int :alias :n :default 10}
           :follow {:coerce :boolean :default true}
           :all {:coerce :boolean}}}

   {:cmds ["logs" "list"]
    :fn logs-list-cmd}

   {:cmds ["events" "tail"]
    :fn events-tail-cmd
    :args->opts [:workflow-id]
    :spec {:file {:alias :f}
           :lines {:coerce :int :alias :n :default 20}
           :follow {:coerce :boolean :default true}
           :filter {:coerce :keyword}
           :all {:coerce :boolean}}}

   {:cmds ["events" "list"]
    :fn events-list-cmd}

   ;; Fleet subcommands (daemon management)
   {:cmds ["fleet" "start"]  :fn fleet-start-cmd}
   {:cmds ["fleet" "stop"]   :fn fleet-stop-cmd}
   {:cmds ["fleet" "status"] :fn fleet-status-cmd}
   {:cmds ["fleet" "add"]    :fn fleet-add-cmd    :args->opts [:repo]}
   {:cmds ["fleet" "remove"] :fn fleet-remove-cmd :args->opts [:repo]}

   ;; PR subcommands
   {:cmds ["pr" "list"]    :fn pr-list-cmd    :spec {:repo {:alias :r}}}
   {:cmds ["pr" "review"]  :fn pr-review-cmd  :args->opts [:url]}
   {:cmds ["pr" "respond"] :fn pr-respond-cmd :args->opts [:url]}
   {:cmds ["pr" "merge"]   :fn pr-merge-cmd   :args->opts [:url]}])

(defn -main
  "CLI entry point."
  [& args]
  (try
    (cli/dispatch dispatch-table args)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (and (= (:type data) :org.babashka/cli)
                 (= (:cause data) :no-match))
          (let [wrong-input (:wrong-input data)
                dispatch (:dispatch data)
                all-commands (:all-commands data)]
            (print-error (str "Unknown command: " (str/join " " (conj (vec dispatch) wrong-input))))
            (println)

            ;; Special case: suggest alternatives for moved commands
            (when (and (= (first dispatch) "fleet")
                       (contains? #{"web" "dashboard" "tui"} wrong-input))
              (println "Did you mean:")
              (println (str "  miniforge " (if (= wrong-input "dashboard") "web" wrong-input)))
              (println))

            (when (seq all-commands)
              (println (str "Available " (if (seq dispatch)
                                           (str "'" (str/join " " dispatch) "' ")
                                           "")
                           "commands:"))
              (doseq [cmd all-commands]
                (println (str "  miniforge " (str/join " " (conj (vec dispatch) cmd)))))
              (println))

            (println "Run 'miniforge help' for more information.")
            (System/exit 1))
          ;; Re-throw if not a no-match error
          (throw e))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test CLI dispatch
  (-main "help")
  (-main "version")
  (-main "doctor")
  (-main "web")
  (-main "tui")
  (-main "config" "init")
  (-main "config" "list")
  (-main "run" "test.spec.edn")

  :end)
