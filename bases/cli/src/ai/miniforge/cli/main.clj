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
   - workflow : Workflow lifecycle (execute, status, list, cancel)
   - status   : Show workflow status
   - fleet    : Multi-repo management (start, stop, status, add, remove, watch, prs)
   - pr       : PR operations (list, review, respond, merge)
   - policy   : Policy pack management (list, show, install)
   - evidence : Evidence bundle management (show, export, list)
   - artifact : Artifact management (provenance, list)
   - etl      : ETL pipeline commands (repo)
   - config   : Configuration management
   - doctor   : System health check
   - version  : Version information

   Note: This is designed to run in Babashka. Malli is bundled with
   Babashka, so schema validation is available here; what we avoid in
   this ns are requires that pull in components with truly BB-hostile
   transitive deps (e.g. native JNI), preferring lightweight dispatch
   to fuller JVM code paths when the CLI only needs thin orchestration."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.workflow-runner.help :as wr-help]
   [ai.miniforge.cli.workflow-runner.help.registry :as wr-help-registry]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.observability :as observability]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.main.commands.run :as cmd-run]
   [ai.miniforge.cli.main.commands.resume :as cmd-resume]
   [ai.miniforge.cli.main.commands.shared :as cmd-shared]
   [ai.miniforge.cli.main.commands.monitoring :as cmd-monitoring]
   [ai.miniforge.cli.main.commands.fleet :as cmd-fleet]
   [ai.miniforge.cli.main.commands.pr :as cmd-pr]
   [ai.miniforge.cli.main.commands.pr-policy-respond :as cmd-pr-policy]
   [ai.miniforge.cli.main.commands.pr-resume-dispatcher :as cmd-pr-resume]
   [ai.miniforge.cli.main.commands.pr-review-monitor :as cmd-pr-review-monitor]
   [ai.miniforge.cli.main.commands.control-plane :as cmd-cp]
   [ai.miniforge.cli.main.commands.scan :as cmd-scan]
   [ai.miniforge.cli.main.commands.init :as cmd-init]
   [ai.miniforge.cli.main.commands.worktree :as cmd-worktree]
   [ai.miniforge.cli.main.commands.runtime :as cmd-runtime]
   ;; N5 command modules
   [ai.miniforge.cli.main.commands.workflow-commands :as cmd-workflow]
   [ai.miniforge.cli.main.commands.policy :as cmd-policy]
   [ai.miniforge.cli.main.commands.evidence :as cmd-evidence]
   [ai.miniforge.cli.main.commands.artifact-cmds :as cmd-artifact]
   [ai.miniforge.cli.main.commands.etl :as cmd-etl]
   ;; GROUP 3b: timeline-based events show
   [ai.miniforge.cli.main.commands.events :as cmd-events]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.mcp-context-server.interface :as mcp-context-server]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.repo-dag.interface :as repo-dag]
   [ai.miniforge.workflow-resume.interface :as wr]
   [ai.miniforge.lsp-mcp-bridge.main :as lsp-bridge]
   [ai.miniforge.lsp-mcp-bridge.tasks :as lsp-tasks]
   [slingshot.slingshot :refer [try+]]))

(defn- optional-composition-var
  "Resolve a provider whose entire component is optional for this CLI product.
   This is the CLI's only late-binding boundary: miniforge-core loads this
   namespace without web-dashboard or TUI components on its classpath."
  [ns-sym var-sym]
  (try
    (require ns-sym)
    (ns-resolve ns-sym var-sym)
    (catch Throwable _ nil)))

(defn- caught-message
  [caught throwable]
  (cond
    (instance? Throwable caught)
    (or (.getMessage ^Throwable caught)
        (some-> caught class .getName)
        "unknown exception")

    throwable
    (or (.getMessage ^Throwable throwable)
        (some-> throwable class .getName)
        "unknown exception")

    :else
    (str caught)))

(defn- create-pr-train-manager
  []
  (try+
    (pr-train/create-manager)
    (catch Object e
      (println (messages/t :web/pr-train-warning
                           {:error (caught-message e (:throwable &throw-context))}))
      nil)))

(defn- create-repo-dag-manager
  []
  (try+
    (repo-dag/create-manager)
    (catch Object e
      (println (messages/t :web/repo-dag-warning
                           {:error (caught-message e (:throwable &throw-context))}))
      nil)))

(defn- optional-web-launcher
  "Compose the dashboard command when the product includes web-dashboard."
  []
  (when-let [start! (optional-composition-var
                     'ai.miniforge.web-dashboard.interface
                     'start!)]
    (fn [{:keys [port]}]
      (let [event-stream (es/create-event-stream)
            pr-train-manager (create-pr-train-manager)
            repo-dag-manager (create-repo-dag-manager)]
        (start! {:port port
                 :event-stream event-stream
                 :pr-train-manager pr-train-manager
                 :repo-dag-manager repo-dag-manager})))))

(def web-launcher
  (optional-web-launcher))

(def web-available?
  (some? web-launcher))

(alter-var-root #'cmd-monitoring/*web-available?* (constantly web-available?))
(alter-var-root #'cmd-monitoring/*start-web-dashboard!* (constantly web-launcher))

;; TUI components loaded conditionally (only in JVM/jlink bundled runtime).
;; This is an optional composition seam: miniforge-core includes the CLI
;; without bundling the JVM TUI component.
(def tui-launcher
  (optional-composition-var 'ai.miniforge.tui-views.interface 'start-standalone-tui!))

(def tui-available?
  (some? tui-launcher))

;; Propagate TUI availability to monitoring commands
(alter-var-root #'cmd-monitoring/*tui-available?* (constantly tui-available?))
(alter-var-root #'cmd-monitoring/*start-standalone-tui!* (constantly tui-launcher))
(when tui-launcher
  (cmd-shared/register-optional-fn!
   'ai.miniforge.tui-views.interface/start-standalone-tui!
   tui-launcher))
(when-let [start-fleet-tui! (optional-composition-var 'ai.miniforge.tui-views.interface
                                                      'start-fleet-tui!)]
  (cmd-shared/register-optional-fn!
   'ai.miniforge.tui-views.interface/start-fleet-tui!
   start-fleet-tui!))

;------------------------------------------------------------------------------ Layer 0
;; Constants and pure helpers

(def version-info
  {:name (app-config/binary-name)
   :version "2026.01.20.1"
   :description (app-config/description)})

(defn current-time-ms
  "Current epoch time in milliseconds. Public so CLI tests can rebind it."
  []
  (System/currentTimeMillis))

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

(defn- timestamp->epoch-ms
  [timestamp]
  (cond
    (instance? java.util.Date timestamp)
    (.getTime ^java.util.Date timestamp)

    (instance? java.time.Instant timestamp)
    (.toEpochMilli ^java.time.Instant timestamp)

    (string? timestamp)
    (try
      (.toEpochMilli (java.time.Instant/parse timestamp))
      (catch Exception _ nil))

    :else nil))

(defn- stale-running?
  [last-updated]
  (when-let [last-updated-ms (timestamp->epoch-ms last-updated)]
    (let [configured-threshold-ms (:running-stale-threshold-ms (app-config/status-config))
          default-threshold-ms (:running-stale-threshold-ms app-config/default-status-config)
          threshold-ms (if (nat-int? configured-threshold-ms)
                         configured-threshold-ms
                         default-threshold-ms)]
      (> (- (current-time-ms) last-updated-ms)
         threshold-ms))))

(defn- reconstructed-status
  [reconstructed last-updated]
  (cond
    (wr/completed? reconstructed) :completed
    (wr/failed? reconstructed) :failed
    (wr/paused? reconstructed) :paused
    (stale-running? last-updated) :stale
    :else :running))

(defn- status-label
  [status]
  (messages/t (keyword "status" (str "value-" (name status)))))

(defn- workflow-status-summary
  [workflow-id]
  (let [events-dir (app-config/events-dir)
        events (es/read-workflow-events-by-id events-dir workflow-id)
        reconstructed (wr/reconstruct-context events-dir workflow-id)
        last-event (last events)]
    (when (anomaly/anomaly? reconstructed)
      (throw (ex-info (:anomaly/message reconstructed) reconstructed)))
    {:workflow-id workflow-id
     :status (reconstructed-status reconstructed (:event/timestamp last-event))
     :spec-name (some-> reconstructed :workflow-spec :name)
     :event-count (:event-count reconstructed)
     :completed-phases (:completed-phases reconstructed)
     :completed-dag-task-count (count (:completed-dag-tasks reconstructed))
     :last-updated (:event/timestamp last-event)}))

(defn- print-workflow-status
  [{:keys [workflow-id status spec-name event-count completed-phases
           completed-dag-task-count last-updated]}]
  (let [unknown (messages/t :status/value-unknown)
        none    (messages/t :status/value-none)]
    (display/print-info (messages/t :status/workflow {:workflow-id workflow-id}))
    (println (messages/t :status/field-status {:value (status-label status)}))
    (println (messages/t :status/field-spec {:value (or spec-name unknown)}))
    (println (messages/t :status/field-events {:value event-count}))
    (println (messages/t :status/field-last-updated {:value (or last-updated unknown)}))
    (println (messages/t :status/field-completed-phases
                         {:value (if (seq completed-phases)
                                   (str/join ", " (map name completed-phases))
                                   none)}))
    (println (messages/t :status/field-completed-dag-tasks
                         {:value completed-dag-task-count}))))

(defn- all-workflow-summaries
  []
  (let [events-dir (app-config/events-dir)]
    (if (fs/exists? events-dir)
      (->> (fs/list-dir events-dir)
           (filter fs/directory?)
           (map #(fs/file-name %))
           (keep (fn [workflow-id]
                   (try
                     (workflow-status-summary workflow-id)
                     (catch Exception _ nil))))
           (sort-by :last-updated #(compare %2 %1)))
      [])))

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

    ;; Container runtime (N11-delta Phase 3)
    (println (display/style (messages/t :runtime/doctor-section-title)
                            :foreground :cyan :bold true))
    (cmd-runtime/print-doctor-runtime-section)
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
      (try
        (print-workflow-status (workflow-status-summary (str workflow-id)))
        (catch Exception e
          (display/print-error (messages/t :status/read-failed
                                           {:message (ex-message e)}))))
      (let [unknown (messages/t :status/value-unknown)
            none    (messages/t :status/value-none)]
        (display/print-info (messages/t :status/all-workflows))
        (if-let [summaries (seq (take 10 (all-workflow-summaries)))]
          (doseq [{:keys [workflow-id status spec-name last-updated]} summaries]
            (println (messages/t :status/summary-row
                                 {:workflow-id (format "%-36s" workflow-id)
                                  :status      (format "%-10s" (status-label status))
                                  :spec-name   (or spec-name unknown)}))
            (println (messages/t :status/summary-last-updated
                                 {:value (or last-updated unknown)})))
          (println (str "  " none)))))))

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

;; Workflow subcommands — spec-driven execution and lifecycle (N5)
(defn workflow-execute-cmd [m] (cmd-workflow/workflow-execute-cmd (get-opts m)))
(defn workflow-inspect-cmd [m] (cmd-workflow/workflow-inspect-cmd (get-opts m)))
(defn workflow-status-cmd  [m] (cmd-workflow/workflow-status-cmd  (get-opts m)))
(defn workflow-cancel-cmd  [m] (cmd-workflow/workflow-cancel-cmd  (get-opts m)))
(defn workflow-gc-scratch-cmd [m] (cmd-workflow/workflow-gc-scratch-cmd (get-opts m)))

;; Chain commands
(defn chain-run-cmd
  [m]
  (let [{:keys [chain-id version spec input-json policy-eval-out quiet]} (get-opts m)]
    (if-not chain-id
      (display/print-error (messages/t :chain-run/usage
                                       {:command (app-config/command-string "chain run <chain-id> [options]")}))
      (try
        (workflow-runner/run-chain!
         (keyword (str/replace chain-id #"^:" ""))
         {:version (or version "latest")
          :spec spec
          :input-json input-json
          :policy-eval-out policy-eval-out
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
(defn events-show-cmd
  "Render a human-readable timeline for a workflow from the local event log.
   Delegates to the GROUP 3b events command module."
  [m]
  (cmd-events/events-show-cmd (get-opts m)))

;; Delegated commands
(defn run-cmd [m] (cmd-run/run-cmd (get-opts m)))

(defn resume-cmd
  "First-class `mf resume <workflow-id>` subcommand. The `<workflow-id>`
   can be passed positionally, or via `--workflow-id`/`-w` / the legacy
   `--resume`/`-r` flag (kept so existing docs keep working)."
  [m]
  (let [opts (get-opts m)
        args (:args m)
        wf-id (or (:workflow-id opts)
                  (:resume opts)
                  (first args))]
    (if (str/blank? (str wf-id))
      (display/print-error (messages/t :resume/missing-workflow-id))
      (cmd-resume/resume-workflow wf-id opts))))
(defn scan-cmd [m] (cmd-scan/scan-cmd (get-opts m)))
(defn init-cmd [m] (cmd-init/init-cmd (get-opts m)))
(defn worktree-cmd [m] (cmd-worktree/worktree-cmd m))
(defn runtime-info-cmd [m] (cmd-runtime/runtime-info-cmd m))
(defn runtime-run-cmd [m] (cmd-runtime/runtime-run-cmd m))
(defn web-cmd [m] (cmd-monitoring/web-cmd (get-opts m)))
(defn tui-cmd [m] (cmd-monitoring/tui-cmd (get-opts m)))
(defn fleet-start-cmd [m] (cmd-fleet/fleet-start-cmd (get-opts m)))
(defn fleet-stop-cmd [m] (cmd-fleet/fleet-stop-cmd (get-opts m)))
(defn fleet-status-cmd [m] (cmd-fleet/fleet-status-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn fleet-add-cmd [m] (cmd-fleet/fleet-add-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn fleet-remove-cmd [m] (cmd-fleet/fleet-remove-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn fleet-watch-cmd [m] (cmd-fleet/fleet-watch-cmd (get-opts m)))
(defn fleet-prs-cmd [m] (cmd-fleet/fleet-prs-cmd (get-opts m) config/default-user-config-path config/default-config))
(defn pr-list-cmd [m]
  (cmd-pr/pr-list-cmd (get-opts m)
                      (fn [config-path]
                        (cmd-fleet/load-config config-path config/default-user-config-path config/default-config))))
(defn pr-review-cmd [m] (cmd-pr/pr-review-cmd (get-opts m)))
(defn pr-respond-cmd [m] (cmd-pr/pr-respond-cmd (get-opts m)))
(defn pr-merge-cmd [m] (cmd-pr/pr-merge-cmd (get-opts m)))
(defn pr-monitor-cmd [m] (cmd-pr/pr-monitor-cmd (get-opts m)))
(defn pr-review-monitor-cmd [m] (cmd-pr-review-monitor/pr-review-monitor-cmd (get-opts m)))
(defn pr-resume-dispatcher-cmd [m] (cmd-pr-resume/pr-resume-dispatcher-cmd (get-opts m)))
(defn pr-policy-respond-cmd [m] (cmd-pr-policy/pr-policy-respond-cmd (get-opts m)))

;; Control Plane commands
(defn cp-status-cmd [m] (cmd-cp/status-cmd (get-opts m)))
(defn cp-decisions-cmd [m] (cmd-cp/decisions-cmd (get-opts m)))
(defn cp-resolve-cmd [m] (cmd-cp/resolve-cmd (get-opts m)))
(defn cp-terminate-cmd [m] (cmd-cp/terminate-cmd (get-opts m)))

;; Policy commands (N5)
(defn policy-list-cmd    [m] (cmd-policy/policy-list-cmd    (get-opts m)))
(defn policy-show-cmd    [m] (cmd-policy/policy-show-cmd    (get-opts m)))
(defn policy-install-cmd [m] (cmd-policy/policy-install-cmd (get-opts m)))

;; Evidence commands (N5)
(defn evidence-list-cmd   [m] (cmd-evidence/evidence-list-cmd   (get-opts m)))
(defn evidence-show-cmd   [m] (cmd-evidence/evidence-show-cmd   (get-opts m)))
(defn evidence-export-cmd [m] (cmd-evidence/evidence-export-cmd (get-opts m)))

;; Artifact commands (N5)
(defn artifact-list-cmd       [m] (cmd-artifact/artifact-list-cmd       (get-opts m)))
(defn artifact-provenance-cmd [m] (cmd-artifact/artifact-provenance-cmd (get-opts m)))

;; ETL commands (N5)
(defn etl-repo-cmd     [m] (cmd-etl/etl-repo-cmd     (get-opts m)))
(defn etl-run-cmd      [m] (cmd-etl/etl-run-cmd      (get-opts m)))
(defn etl-list-cmd     [m] (cmd-etl/etl-list-cmd     (get-opts m)))
(defn etl-validate-cmd [m] (cmd-etl/etl-validate-cmd (get-opts m)))

(defn hook-eval-cmd
  "Evaluate a tool-use request from a Claude PreToolUse hook.

   Reads JSON from stdin, evaluates against policy, writes decision to stdout."
  [_m]
  (System/exit (agent/hook-eval-stdin!)))

(defn context-server-cmd
  "Run the MCP context server (internal — spawned as subprocess by the agent).

   Reads JSON-RPC 2.0 from stdin, serves context_read/context_grep/context_glob
   from a pre-populated cache with filesystem fallback, plus context_write which
   writes to --workdir (the agent's worktree). Invoked automatically; not
   intended for direct user use."
  [m]
  (let [{:keys [artifact-dir source-root workdir]} (get-opts m)]
    (mcp-context-server/start-server artifact-dir source-root workdir)))

(defn lsp-mcp-bridge-cmd
  "Run the LSP-to-MCP bridge server (spawned by Claude Code/Desktop/Codex as MCP server).

   Reads MINIFORGE_PROJECT_DIR env for the project root. Invoked automatically
   by the MCP client; not intended for direct user use."
  [_m]
  (lsp-bridge/-main))

(defn lsp-status-cmd [_m]
  (lsp-tasks/status))

(defn lsp-install-cmd [m]
  (lsp-tasks/install (:args m)))

(defn lsp-setup-cmd [m]
  (lsp-tasks/setup (:args m)))

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
    :spec {:artifact-dir {:alias :a :require true}
           :source-root  {:alias :s}
           :workdir      {:alias :w}}}

   ;; LSP-to-MCP bridge server (internal — spawned by Claude Code/Desktop/Codex)
   {:cmds ["lsp-mcp-bridge"]
    :fn lsp-mcp-bridge-cmd}

   ;; LSP management commands (user-facing)
   {:cmds ["lsp"]          :fn help-cmd}
   {:cmds ["lsp" "status"] :fn lsp-status-cmd}
   {:cmds ["lsp" "install"] :fn lsp-install-cmd}
   {:cmds ["lsp" "setup"] :fn lsp-setup-cmd}

   ;; Init command — repo analysis and config generation
   {:cmds ["init"]
    :fn init-cmd
    :args->opts [:repo]}

   ;; Worktree helpers
   {:cmds ["worktree"]
    :fn worktree-cmd
    :spec {:path {:alias :p}}}

   ;; Runtime adapter (N11-delta Phase 3) — info / pass-through run
   {:cmds ["runtime"]      :fn help-cmd}
   {:cmds ["runtime" "info"] :fn runtime-info-cmd}
   {:cmds ["runtime" "run"]  :fn runtime-run-cmd}

   ;; Scan command — compliance scanner
   {:cmds ["scan"]
    :fn scan-cmd
    :args->opts [:repo]
    :spec {:pack      {:alias :p}
           :rules     {:alias :r}
           :since     {:alias :s}
           :standards {:default "standards/miniforge"}
           :execute   {:coerce :boolean :alias :x}
           :report    {:coerce :boolean :default true}
           :no-lint   {:coerce :boolean}
           :semantic  {:coerce :boolean}}}

   ;; Run command
   {:cmds ["run"]
    :fn run-cmd
    :args->opts [:spec]
    :spec {:interactive     {:coerce :boolean :alias :i}
           :worktree        {:alias :w}
           :resume          {:alias :r}
           :backend         {:coerce :keyword :alias :b}
           :execution-mode  {:coerce :keyword :alias :m}}}

   ;; Resume — first-class subcommand. Reuses `run --resume`'s
   ;; underlying logic through the workflow-resume component.
   {:cmds ["resume"]
    :fn resume-cmd
    :args->opts [:workflow-id]
    :spec {:workflow-id {:alias :w}
           :quiet       {:coerce :boolean :alias :q}}}

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

   ;; Workflow parent — `mf workflow --help` / `mf workflow` lists the
   ;; group's subcommands with their one-line summaries.
   {:cmds ["workflow"]
    :fn (wr-help/group-handler wr-help-registry/workflow-group-help)}

   ;; Workflow commands (each subcommand carries a `--help` / `-h`
   ;; flag wired through `wr-help/handler-with-help`; the spec from
   ;; `wr-help-registry/subcommands` is the single source of truth for
   ;; the auto-generated flag table).
   {:cmds ["workflow" "run"]
    :fn (wr-help/handler-with-help :workflow-run workflow-run-cmd)
    :args->opts [:workflow-id]
    :spec (wr-help-registry/spec-for :workflow-run)}

   {:cmds ["workflow" "list"]
    :fn (wr-help/handler-with-help :workflow-list workflow-list-cmd)
    :spec (wr-help-registry/spec-for :workflow-list)}

   ;; Workflow subcommands — spec-driven execution and lifecycle (N5)
   {:cmds ["workflow" "execute"]
    :fn (wr-help/handler-with-help :workflow-execute workflow-execute-cmd)
    :args->opts [:spec]
    :spec (wr-help-registry/spec-for :workflow-execute)}

   {:cmds ["workflow" "status"]
    :fn (wr-help/handler-with-help :workflow-status workflow-status-cmd)
    :args->opts [:id]
    :spec (wr-help-registry/spec-for :workflow-status)}

   {:cmds ["workflow" "inspect"]
    :fn (wr-help/handler-with-help :workflow-inspect workflow-inspect-cmd)
    :args->opts [:path]
    :spec (wr-help-registry/spec-for :workflow-inspect)}

   {:cmds ["workflow" "cancel"]
    :fn (wr-help/handler-with-help :workflow-cancel workflow-cancel-cmd)
    :args->opts [:id]
    :spec (wr-help-registry/spec-for :workflow-cancel)}

   {:cmds ["workflow" "gc-scratch"]
    :fn (wr-help/handler-with-help :workflow-gc-scratch workflow-gc-scratch-cmd)
    :spec (wr-help-registry/spec-for :workflow-gc-scratch)}

   ;; Chain parent — `mf chain --help` / `mf chain` lists the group's
   ;; subcommands with their one-line summaries.
   {:cmds ["chain"]
    :fn (wr-help/group-handler wr-help-registry/chain-group-help)}

   ;; Chain commands
   {:cmds ["chain" "run"]
    :fn (wr-help/handler-with-help :chain-run chain-run-cmd)
    :args->opts [:chain-id]
    :spec (wr-help-registry/spec-for :chain-run)}

   {:cmds ["chain" "list"]
    :fn (wr-help/handler-with-help :chain-list chain-list-cmd)
    :spec (wr-help-registry/spec-for :chain-list)}

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

   {:cmds ["events" "show"]
    :fn events-show-cmd
    :args->opts [:workflow-id]
    :spec {:gap-threshold {:coerce :int    :alias :g :default 60}
           :raw           {:coerce :boolean :alias :r :default false}}}

   ;; Fleet subcommands (daemon management + watch/prs — N5)
   {:cmds ["fleet" "start"]  :fn fleet-start-cmd}
   {:cmds ["fleet" "stop"]   :fn fleet-stop-cmd}
   {:cmds ["fleet" "status"] :fn fleet-status-cmd}
   {:cmds ["fleet" "add"]    :fn fleet-add-cmd    :args->opts [:repo]}
   {:cmds ["fleet" "remove"] :fn fleet-remove-cmd :args->opts [:repo]}
   {:cmds ["fleet" "watch"]  :fn fleet-watch-cmd}
   {:cmds ["fleet" "prs"]    :fn fleet-prs-cmd    :spec {:repo {:alias :r}}}

   ;; PR subcommands
   {:cmds ["pr" "list"]    :fn pr-list-cmd    :spec {:repo {:alias :r}}}
   {:cmds ["pr" "review"]  :fn pr-review-cmd  :args->opts [:url]
    :spec {:url       {:alias :u}
           :repo      {}
           :base      {:alias :b}
           :standards {:default "standards/miniforge"}
           :pack      {:alias :p}
           :rules     {:alias :r}
           :out       {:default "table"}
           :post      {:coerce :boolean}
           :pr-number {:coerce :long}}}
   {:cmds ["pr" "respond"] :fn pr-respond-cmd :args->opts [:url]}
   {:cmds ["pr" "merge"]   :fn pr-merge-cmd   :args->opts [:url]}
   {:cmds ["pr" "monitor"] :fn pr-monitor-cmd :spec {:author {:alias :a}
                                                      :poll-interval {:alias :p}
                                                      :repo {}}}

   ;; N13 §2.2 Standards Reviewer auto-trigger (single-pass v0)
   {:cmds ["pr" "review-monitor"]
    :fn pr-review-monitor-cmd
    :spec {:repo      {}
           :author    {:alias :a}
           :standards {:default "standards/miniforge"}
           :pack      {:alias :p}
           :rules     {:alias :r}
           :once      {:coerce :boolean :default true}}}

   ;; N13 §2.7 Resume Signal Dispatcher (single-pass v0)
   {:cmds ["pr" "resume-dispatch"]
    :fn pr-resume-dispatcher-cmd
    :spec {:repo {}
           :once {:coerce :boolean :default true}}}

   ;; N13 §2.5 Comment Response Agent — policy-eval (deterministic) path
   {:cmds ["pr" "policy-respond"]
    :fn pr-policy-respond-cmd
    :args->opts [:url]}

   ;; Control Plane subcommands
   {:cmds ["control-plane" "status"]    :fn cp-status-cmd}
   {:cmds ["control-plane" "decisions"] :fn cp-decisions-cmd}
   {:cmds ["control-plane" "resolve"]   :fn cp-resolve-cmd
    :args->opts [:decision-id :resolution]
    :spec {:comment {:alias :c}}}
   {:cmds ["control-plane" "terminate"] :fn cp-terminate-cmd
    :args->opts [:agent-id]}

   ;; Policy pack commands (N5)
   {:cmds ["policy"]           :fn help-cmd}
   {:cmds ["policy" "list"]    :fn policy-list-cmd}
   {:cmds ["policy" "show"]    :fn policy-show-cmd    :args->opts [:pack-id]}
   {:cmds ["policy" "install"] :fn policy-install-cmd :args->opts [:path]}

   ;; Evidence bundle commands (N5)
   {:cmds ["evidence"]           :fn help-cmd}
   {:cmds ["evidence" "list"]    :fn evidence-list-cmd}
   {:cmds ["evidence" "show"]    :fn evidence-show-cmd   :args->opts [:id]}
   {:cmds ["evidence" "export"]  :fn evidence-export-cmd :args->opts [:id :format]}

   ;; Artifact commands (N5)
   {:cmds ["artifact"]              :fn help-cmd}
   {:cmds ["artifact" "list"]       :fn artifact-list-cmd}
   {:cmds ["artifact" "provenance"] :fn artifact-provenance-cmd :args->opts [:id]}

   ;; ETL commands (N5)
   {:cmds ["etl"]             :fn help-cmd}
   {:cmds ["etl" "repo"]      :fn etl-repo-cmd     :args->opts [:url]}
   {:cmds ["etl" "run"]       :fn etl-run-cmd      :args->opts [:pack]}
   {:cmds ["etl" "list"]      :fn etl-list-cmd     :args->opts [:paths]}
   {:cmds ["etl" "validate"]  :fn etl-validate-cmd :args->opts [:pack]}])

(defn- handle-unknown-command
  "Print help for an unrecognized CLI command."
  [{:keys [wrong-input dispatch all-commands]}]
  (display/print-error (messages/t :main/unknown-command
                                   {:command (str/join " " (conj (vec dispatch) wrong-input))}))
  (println)
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

(defn -main
  "CLI entry point."
  [& args]
  (try+
    (cli/dispatch dispatch-table args)
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (catch [:type :org.babashka/cli :cause :no-match] data
      (handle-unknown-command data))))

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
