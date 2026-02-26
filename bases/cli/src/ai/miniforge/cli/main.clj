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
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.observability :as observability]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.main.commands.run :as cmd-run]
   [ai.miniforge.cli.main.commands.monitoring :as cmd-monitoring]
   [ai.miniforge.cli.main.commands.fleet :as cmd-fleet]
   [ai.miniforge.cli.main.commands.pr :as cmd-pr]))

;; TUI components loaded conditionally (only in JVM/jlink bundled runtime)
(def tui-available?
  (try
    (require '[ai.miniforge.event-stream.interface :as es])
    (require '[ai.miniforge.tui-views.interface :as tui])
    true
    (catch Exception _ false)))

;; Propagate TUI availability to monitoring commands
(alter-var-root #'cmd-monitoring/*tui-available?* (constantly tui-available?))

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

(defn- get-opts
  "Extract opts from dispatch result."
  [m]
  (if (contains? m :opts)
    (:opts m)
    m))

(defn- check-command
  "Check if a command is available."
  [cmd]
  (let [{:keys [exit]} (process/sh "which" cmd)]
    (zero? exit)))

;------------------------------------------------------------------------------ Layer 1
;; Command implementations

(defn version-cmd
  [_m]
  (println (str (:name version-info) " " (:version version-info)))
  (println (:description version-info)))

(defn doctor-cmd
  [_m]
  (println "\n" (display/style "Miniforge System Check" :foreground :cyan :bold true) "\n")

  (let [checks [["bb" "Babashka" "Required for CLI"]
                ["gum" "Charm Gum" "Required for TUI"]
                ["git" "Git" "Required for version control"]
                ["gh" "GitHub CLI" "Required for PR operations"]
                ["claude" "Claude CLI" "Required for LLM backend"]]]

    (doseq [[cmd name desc] checks]
      (let [available? (check-command cmd)
            status (if available?
                     (display/style "✓" :foreground :green)
                     (display/style "✗" :foreground :red))
            name-styled (if available? name (display/style name :foreground :red))]
        (println (format "  %s %-12s %s" status name-styled desc))))

    (println)

    ;; Check config
    (if (fs/exists? default-config-path)
      (println (display/style "✓" :foreground :green) "Config file exists at" default-config-path)
      (println (display/style "!" :foreground :yellow) "No config file. Run 'miniforge config init'"))

    (println)))

(defn status-cmd
  [m]
  (let [{:keys [workflow-id]} (get-opts m)]
    (if workflow-id
      (do
        (display/print-info (str "Status for workflow: " workflow-id))
        (println "TODO: Query workflow component"))
      (do
        (display/print-info "All workflows:")
        (println "TODO: List active workflows")))))

;; Config commands — one-liner delegates
(defn config-init-cmd [m] (config/cmd-init (get-opts m)))
(defn config-list-cmd [m] (config/cmd-list (get-opts m)))
(defn config-get-cmd [m] (config/cmd-get (get-opts m)))
(defn config-set-cmd [m] (config/cmd-set (get-opts m)))
(defn config-edit-cmd [m] (config/cmd-edit (get-opts m)))
(defn config-reset-cmd [m] (config/cmd-reset (get-opts m)))
(defn config-backends-cmd [m] (config/cmd-backends (get-opts m)))
(defn config-backend-cmd [m] (config/cmd-backend (get-opts m)))
(defn config-validate-cmd [m] (config/cmd-validate (get-opts m)))

;; Workflow commands
(defn workflow-run-cmd
  [m]
  (let [{:keys [workflow-id version input input-json output quiet dashboard-url]} (get-opts m)]
    (if-not workflow-id
      (display/print-error "Usage: miniforge workflow run <workflow-id> [options]")
      (try
        (workflow-runner/run-workflow!
         (keyword (str/replace workflow-id #"^:" ""))
         {:version (or version "latest")
          :input input
          :input-json input-json
          :output (or output :pretty)
          :quiet (boolean quiet)
          :dashboard-url dashboard-url})
        (catch Exception e
          (display/print-error (str "Workflow execution failed: " (ex-message e))))))))

(defn workflow-list-cmd [_m] (workflow-runner/list-workflows!))

;; Observability commands
(defn logs-tail-cmd [m] (observability/handle-logs (assoc (get-opts m) :subcommand "tail")))
(defn logs-list-cmd [_m] (observability/handle-logs {:subcommand "list"}))
(defn events-tail-cmd [m] (observability/handle-events (assoc (get-opts m) :subcommand "tail")))
(defn events-list-cmd [_m] (observability/handle-events {:subcommand "list"}))

;; Delegated commands
(defn run-cmd [m] (cmd-run/run-cmd (get-opts m)))
(defn web-cmd [m] (cmd-monitoring/web-cmd (get-opts m)))
(defn tui-cmd [m] (cmd-monitoring/tui-cmd (get-opts m)))
(defn fleet-start-cmd [m] (cmd-fleet/fleet-start-cmd (get-opts m)))
(defn fleet-stop-cmd [m] (cmd-fleet/fleet-stop-cmd (get-opts m)))
(defn fleet-status-cmd [m] (cmd-fleet/fleet-status-cmd (get-opts m) default-config-path default-config))
(defn fleet-add-cmd [m] (cmd-fleet/fleet-add-cmd (get-opts m) default-config-path default-config))
(defn fleet-remove-cmd [m] (cmd-fleet/fleet-remove-cmd (get-opts m) default-config-path default-config))
(defn pr-list-cmd [m]
  (cmd-pr/pr-list-cmd (get-opts m)
                      (fn [config-path]
                        (cmd-fleet/load-config config-path default-config-path default-config))))
(defn pr-review-cmd [m] (cmd-pr/pr-review-cmd (get-opts m)))
(defn pr-respond-cmd [m] (cmd-pr/pr-respond-cmd (get-opts m)))
(defn pr-merge-cmd [m] (cmd-pr/pr-merge-cmd (get-opts m)))

(defn help-cmd
  [_m]
  (println "
miniforge - AI-powered software development workflows

Usage: miniforge <command> [options]

Commands:
  run <spec-file>     Execute a workflow from a spec file
    --interactive     Interactive mode (chat-based)
    --tui             Monitor workflow in terminal UI
    --worktree <path> Custom worktree path

  status [id]         Show workflow status

  dashboard           Start workflow monitoring dashboard (default port: 7878)
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
  miniforge web                        # Start web dashboard (port 7878)
  miniforge web --port 3000 --open     # Custom port, auto-open browser
  miniforge tui                        # Start terminal UI (requires miniforge-tui)
  miniforge workflow list
  miniforge workflow run :simple-v2
  miniforge workflow run :canonical-sdlc-v1 -i input.edn
  miniforge workflow run :workflow-id --input-json '{\"task\": \"Build feature\"}'
  miniforge fleet add myorg/myrepo
  miniforge pr review https://github.com/org/repo/pull/123
"))

;------------------------------------------------------------------------------ Layer 2
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
           :tui         {:coerce :boolean}
           :worktree    {:alias :w}}}

   ;; Status command
   {:cmds ["status"]
    :fn status-cmd
    :args->opts [:workflow-id]}

   ;; Monitoring commands
   {:cmds ["web"]
    :fn web-cmd
    :spec {:port {:coerce :int :alias :p :default 7878}
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
   {:cmds ["config"] :fn help-cmd}
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
            (display/print-error (str "Unknown command: " (str/join " " (conj (vec dispatch) wrong-input))))
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
