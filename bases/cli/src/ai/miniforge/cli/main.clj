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
  "CLI entry point for MiniForge Core and product CLIs.

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
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.observability :as observability]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.main.commands.run :as cmd-run]
   [ai.miniforge.cli.main.commands.monitoring :as cmd-monitoring]
   [ai.miniforge.cli.main.commands.fleet :as cmd-fleet]
   [ai.miniforge.cli.main.commands.pr :as cmd-pr]
   [ai.miniforge.cli.main.commands.control-plane :as cmd-cp]))

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
  {:name (app-config/binary-name)
   :version "2026.01.20.1"
   :description (app-config/description)})

(defn get-opts
  "Extract opts from dispatch result."
  [m]
  (if (contains? m :opts)
    (:opts m)
    m))

(defn check-command
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
  (println "\n" (display/style (app-config/system-check-title) :foreground :cyan :bold true) "\n")

  (let [checks (messages/t :doctor/checks)]

    (doseq [[cmd name desc] checks]
      (let [available? (check-command cmd)
            status (if available?
                     (display/style "✓" :foreground :green)
                     (display/style "✗" :foreground :red))
            name-styled (if available? name (display/style name :foreground :red))]
        (println (format "  %s %-12s %s" status name-styled desc))))

    (println)

    ;; Check config
    (if (fs/exists? config/default-user-config-path)
      (println (display/style "✓" :foreground :green)
               (messages/t :doctor/config-exists
                           {:config-path config/default-user-config-path}))
      (println (display/style "!" :foreground :yellow)
               (messages/t :doctor/no-config
                           {:command (app-config/command-string "config init")})))

    (println)))

(defn status-cmd
  [m]
  (let [{:keys [workflow-id]} (get-opts m)]
    (if workflow-id
      (do
        (display/print-info (messages/t :status/workflow {:workflow-id workflow-id}))
        (println (messages/t :status/workflow-todo)))
      (do
        (display/print-info (messages/t :status/all-workflows))
        (println (messages/t :status/all-workflows-todo))))))

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
      (display/print-error (messages/t :workflow-run/usage
                                       {:command (app-config/command-string "workflow run <workflow-id> [options]")}))
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
          (display/print-error (messages/t :workflow-run/failed
                                           {:error (ex-message e)})))))))

(defn workflow-list-cmd [_m] (workflow-runner/list-workflows!))

;; Chain commands
(defn chain-run-cmd
  [m]
  (let [{:keys [chain-id version spec input-json quiet]} (get-opts m)]
    (if-not chain-id
      (display/print-error (messages/t :chain-run/usage
                                       {:command (app-config/command-string "chain run <chain-id> [options]")}))
      (try
        (workflow-runner/run-chain!
         (keyword (str/replace chain-id #"^:" ""))
         {:version (or version "latest")
          :spec spec
          :input-json input-json
          :quiet (boolean quiet)})
        (catch Exception e
          (display/print-error (messages/t :chain-run/failed
                                           {:error (ex-message e)})))))))

(defn chain-list-cmd [_m] (workflow-runner/list-chains!))

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
(defn fleet-status-cmd [m] (cmd-fleet/fleet-status-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn fleet-add-cmd [m] (cmd-fleet/fleet-add-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn fleet-remove-cmd [m] (cmd-fleet/fleet-remove-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn pr-list-cmd [m]
  (cmd-pr/pr-list-cmd (get-opts m)
                      (fn [config-path]
                        (cmd-fleet/load-config config-path config/default-user-config-path config/default-config))))
(defn pr-review-cmd [m] (cmd-pr/pr-review-cmd (get-opts m)))
(defn pr-respond-cmd [m] (cmd-pr/pr-respond-cmd (get-opts m)))
(defn pr-merge-cmd [m] (cmd-pr/pr-merge-cmd (get-opts m)))

;; Control Plane commands
(defn cp-status-cmd [m] (cmd-cp/status-cmd (get-opts m)))
(defn cp-decisions-cmd [m] (cmd-cp/decisions-cmd (get-opts m)))
(defn cp-resolve-cmd [m] (cmd-cp/resolve-cmd (get-opts m)))
(defn cp-terminate-cmd [m] (cmd-cp/terminate-cmd (get-opts m)))

(defn hook-eval-cmd
  "Evaluate a tool-use request from a Claude PreToolUse hook.

   Reads JSON from stdin, evaluates against policy, writes decision to stdout."
  [_m]
  (let [hook-eval! (requiring-resolve 'ai.miniforge.agent.tool-supervisor/hook-eval-stdin!)]
    (System/exit (hook-eval!))))

(defn context-server-cmd
  "Run the MCP context server (internal — spawned as subprocess by the agent).

   Reads JSON-RPC 2.0 from stdin, serves context_read/context_grep/context_glob
   from a pre-populated cache with filesystem fallback. Invoked automatically;
   not intended for direct user use."
  [m]
  (let [{:keys [artifact-dir]} (get-opts m)
        start-server! (requiring-resolve 'ai.miniforge.mcp-context-server.interface/start-server)]
    (start-server! artifact-dir)))

(defn lsp-mcp-bridge-cmd
  "Run the LSP-to-MCP bridge server (spawned by Claude Code/Desktop/Codex as MCP server).

   Reads MINIFORGE_PROJECT_DIR env for the project root. Invoked automatically
   by the MCP client; not intended for direct user use."
  [_m]
  (let [main! (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.main/-main)]
    (main!)))

(defn lsp-status-cmd [_m]
  (let [status! (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.tasks/status)]
    (status!)))

(defn lsp-install-cmd [m]
  (let [install! (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.tasks/install)]
    (install! (:args m))))

(defn lsp-setup-cmd [m]
  (let [opts     (get-opts m)
        args     (cond-> []
                   (:claude-code opts)    (conj "--claude-code")
                   (:claude-desktop opts) (conj "--claude-desktop")
                   (:codex opts)          (conj "--codex"))
        setup!   (requiring-resolve 'ai.miniforge.lsp-mcp-bridge.tasks/setup)]
    (setup! args)))

(defn help-cmd
  [_m]
  (let [binary-name (app-config/binary-name)
        description (app-config/description)
        command-lines (messages/t :help/command-lines)
        note (messages/t :help/note {:binary binary-name})
        tui-install (when-let [tui-package (app-config/tui-package)]
                      (messages/t :help/tui-install {:tui-package tui-package}))]
    (println (str "\n"
                  (messages/t :help/title {:binary binary-name
                                           :description description})
                  "\n\n"
                  (messages/t :help/usage {:binary binary-name})
                  "\n\n"
                  (str/join "\n" command-lines)
                  "\n\nNote:\n  "
                  note
                  (when tui-install
                    (str "\n  " tui-install))
                  "\n\nExamples:\n"
                  (str/join "\n" (map #(str "  " (app-config/command-string %))
                                      (app-config/help-examples)))
                  "\n"))))

;------------------------------------------------------------------------------ Layer 2
;; CLI dispatch

(def dispatch-table
  [{:cmds ["version"] :fn version-cmd}
   {:cmds ["doctor"]  :fn doctor-cmd}
   {:cmds ["help"]    :fn help-cmd}
   {:cmds []          :fn help-cmd}  ; default

   ;; Tool-use supervision hook (Claude PreToolUse)
   {:cmds ["hook-eval"]
    :fn hook-eval-cmd}

   ;; MCP context server (internal — spawned as subprocess by agent)
   {:cmds ["context-server"]
    :fn context-server-cmd
    :spec {:artifact-dir {:alias :a :require true}}}

   ;; LSP-to-MCP bridge server (internal — spawned by Claude Code/Desktop/Codex)
   {:cmds ["lsp-mcp-bridge"]
    :fn lsp-mcp-bridge-cmd}

   ;; LSP management commands (user-facing)
   {:cmds ["lsp"]          :fn help-cmd}
   {:cmds ["lsp" "status"] :fn lsp-status-cmd}
   {:cmds ["lsp" "install"] :fn lsp-install-cmd}
   {:cmds ["lsp" "setup"]
    :fn lsp-setup-cmd
    :spec {:claude-code    {:coerce :boolean :alias :c}
           :claude-desktop {:coerce :boolean :alias :d}
           :codex          {:coerce :boolean :alias :x}}}

   ;; Run command
   {:cmds ["run"]
    :fn run-cmd
    :args->opts [:spec]
    :spec {:interactive {:coerce :boolean :alias :i}
           :worktree    {:alias :w}
           :resume      {:alias :r}
           :backend     {:coerce :keyword :alias :b}}}

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
    :fn tui-cmd
    :spec {:debug {:coerce :boolean :alias :d :default false}}}

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

   ;; Chain commands
   {:cmds ["chain" "run"]
    :fn chain-run-cmd
    :args->opts [:chain-id]
    :spec {:version {:coerce :string :alias :v :default "latest"}
           :spec {:alias :s}
           :input-json {}
           :quiet {:coerce :boolean :alias :q}}}

   {:cmds ["chain" "list"]
    :fn chain-list-cmd}

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
   {:cmds ["pr" "merge"]   :fn pr-merge-cmd   :args->opts [:url]}

   ;; Control Plane subcommands
   {:cmds ["control-plane" "status"]    :fn cp-status-cmd}
   {:cmds ["control-plane" "decisions"] :fn cp-decisions-cmd}
   {:cmds ["control-plane" "resolve"]   :fn cp-resolve-cmd
    :args->opts [:decision-id :resolution]
    :spec {:comment {:alias :c}}}
   {:cmds ["control-plane" "terminate"] :fn cp-terminate-cmd
    :args->opts [:agent-id]}])

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
            (display/print-error (messages/t :main/unknown-command
                                             {:command (str/join " " (conj (vec dispatch)
                                                                           wrong-input))}))
            (println)

            ;; Special case: suggest alternatives for moved commands
            (when (and (= (first dispatch) "fleet")
                       (contains? #{"web" "dashboard" "tui"} wrong-input))
              (println (messages/t :main/did-you-mean))
              (println (str "  " (app-config/command-string (if (= wrong-input "dashboard") "web" wrong-input))))
              (println))

            (when (seq all-commands)
              (println (messages/t :main/available-commands
                                   {:scope (if (seq dispatch)
                                             (str "'" (str/join " " dispatch) "' ")
                                             "")}))
              (doseq [cmd all-commands]
                (println (str "  " (app-config/command-string (str/join " " (conj (vec dispatch) cmd))))))
              (println))

            (println (messages/t :main/run-help
                                 {:command (app-config/command-string "help")}))
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
