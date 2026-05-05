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

(ns ai.miniforge.cli.workflow-runner
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.resource-config :as resource-config]
   [ai.miniforge.cli.workflow-recommender :as recommender]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.response.interface :as response]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0
;; Work spec kanban lifecycle

(def ^:private work-dirs
  "Kanban folder structure under work/."
  {:in-progress "work/in-progress"
   :done        "work/done"
   :failed      "work/failed"})

(defn- work-spec?
  "True when the spec provenance points to a file under work/."
  [provenance]
  (when-let [source (:source-file provenance)]
    (str/starts-with? (str source) "work/")))

(defn- move-spec!
  "Move a work spec file to the target kanban folder.
   No-op if the spec isn't under work/ or the file doesn't exist."
  [provenance target-key]
  (when (work-spec? provenance)
    (let [source (str (:source-file provenance))
          target-dir (get work-dirs target-key)]
      (when (and target-dir (fs/exists? source))
        (fs/create-dirs target-dir)
        (let [target (str target-dir "/" (fs/file-name source))]
          (fs/move source target {:replace-existing true})
          target)))))

(defn move-spec-to-in-progress!
  "Move a work spec to in-progress when execution starts.
   Returns updated provenance with new source-file path,
   or the original provenance if the move was a no-op."
  [provenance]
  (if-let [new-path (move-spec! provenance :in-progress)]
    (assoc provenance :source-file new-path)
    provenance))

(defn move-spec-on-completion!
  "Move a work spec to done or failed based on workflow result."
  [provenance result]
  (if (phase/succeeded? result)
    (move-spec! provenance :done)
    (move-spec! provenance :failed)))

;------------------------------------------------------------------------------ Layer 0.5
;; Meta-loop context — process-scoped, accumulates metrics across workflows

(defonce ^:private meta-loop-ctx
  ;; Lazily initialized on first workflow completion.
  ;; Uses a dedicated operator-level event stream (no workflow-id → operator.edn).
  (atom nil))

(defn- get-or-init-meta-loop-ctx! []
  (or @meta-loop-ctx
      (let [operator-stream (es/create-event-stream)
            _supervisor (supervisory/attach! operator-stream)
            ctx (agent/create-meta-loop-context operator-stream)]
        (reset! meta-loop-ctx ctx)
        ctx)))

(defn- trigger-meta-loop-after-workflow!
  "Record workflow outcome and run a background meta-loop cycle.
   Failures are swallowed — the meta-loop must never crash the workflow runner."
  [workflow-id status failure-class]
  (future
    (try
      (let [ctx (get-or-init-meta-loop-ctx!)]
        (agent/record-workflow-outcome! ctx workflow-id status failure-class)
        (agent/run-cycle-from-context! ctx))
      (catch Exception _e nil))))

;------------------------------------------------------------------------------ Layer 0.6
;; Workflow interface resolution and pipeline helpers

(defn resolve-workflow-interface []
  {:load-workflow workflow/load-workflow
   :run-pipeline  workflow/run-pipeline})

(defn create-phase-callbacks [_quiet]
  ;; Phase progress is handled by the event-stream subscription
  ;; (display/start-progress!). Callbacks retained as extension point.
  {})

(defn load-and-validate-workflow [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (response/throw-anomaly! :anomalies/not-found
                               (messages/t :workflow-runner/not-found {:workflow-id workflow-id})
                               {:workflow-id workflow-id :version version}))
    (when (and validation (not (:valid? validation)))
      (response/throw-anomaly! :anomalies.workflow/invalid-config
                               (messages/t :workflow-runner/validation-failed {:errors (:errors validation)})
                               {:workflow-id workflow-id :validation validation}))
    workflow))

(defn create-artifact-store [quiet]
  (try
    (artifact/create-transit-store)
    (catch Exception _e
      (when-not quiet
        (println (display/colorize :yellow (messages/t :workflow-runner/artifact-store-warning))))
      nil)))

(defn- valid-source-root?
  [source-root]
  (and source-root
       (fs/exists? source-root)
       (fs/exists? (fs/path source-root ".git"))))

(defn- normalize-path
  [path]
  (when path
    (let [resolved (-> path
                       fs/path
                       fs/absolutize)]
      (str (if (fs/exists? resolved)
             (fs/canonicalize resolved)
             (.normalize resolved))))))

(defn- source-dir-under-root?
  [source-dir source-root]
  (let [source-dir-path (some-> source-dir normalize-path fs/path)
        source-root-path (some-> source-root normalize-path fs/path)]
    (or (nil? source-dir-path)
        (nil? source-root-path)
        (.startsWith source-dir-path source-root-path))))

(defn- assert-valid-source-root!
  [context]
  (let [source-root (:source-root context)]
    (when-not (valid-source-root? source-root)
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Invalid workflow source root: " source-root)
                               {:source-root source-root
                                :worktree-path (:worktree-path context)}))))

(defn- assert-execution-worktree!
  [context]
  (let [expected (normalize-path (get-in context [:execution/opts :worktree-path]))
        actual (normalize-path (:worktree-path context))]
    (when (and expected actual (not= expected actual))
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Execution worktree mismatch: expected "
                                    expected " but runtime is using " actual)
                               {:expected-worktree expected
                                :actual-worktree actual
                                :source-root (:source-root context)}))))

(defn- assert-source-dir-alignment!
  [spec context]
  (let [source-dir (:spec/source-dir spec)
        source-root (:source-root context)]
    (when-not (source-dir-under-root? source-dir source-root)
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Spec source directory is outside the workflow source root: "
                                    source-dir)
                               {:source-dir source-dir
                                :source-root source-root
                                :worktree-path (:worktree-path context)}))))

(defn- assert-runtime-alignment!
  [spec context]
  (assert-valid-source-root! context)
  (assert-execution-worktree! context)
  (assert-source-dir-alignment! spec context))

(defn- print-colored-lines!
  [quiet lines]
  (when-not quiet
    (doseq [[color line] lines]
      (println (display/colorize color line)))))

(defn- runtime-provenance-lines
  [context]
  (concat
   [[:cyan (messages/t :workflow-runner/runtime-source
                       {:path (:source-root context)})]
    [:cyan (messages/t :workflow-runner/runtime-worktree
                       {:path (:worktree-path context)})]]
   (when-let [branch (:git-branch context)]
     [[:cyan (messages/t :workflow-runner/runtime-branch {:branch branch})]])
   (when-let [commit (:git-commit context)]
     [[:cyan (messages/t :workflow-runner/runtime-commit {:commit commit})]])
   (when-let [upstream (:git-upstream context)]
     [[:cyan (messages/t :workflow-runner/runtime-upstream {:upstream upstream})]])
   (when (:git-detached? context)
     [[:yellow (messages/t :workflow-runner/runtime-detached-warning)]])
   (when (:git-dirty? context)
     [[:yellow (messages/t :workflow-runner/runtime-dirty-warning)]])))

(defn- print-runtime-provenance!
  [quiet context]
  (print-colored-lines! quiet (runtime-provenance-lines context)))

(def ^:private workflow-runner-config
  (delay
    (resource-config/merged-resource-config "config/cli/workflow-runner.edn"
                                            :workflow-runner
                                            {})))

(defn- backend-preflight-config []
  (:backend-preflight @workflow-runner-config))

(defn- backend-preflight-prompt []
  (:prompt (backend-preflight-config)))

(defn- backend-preflight-timeout-ms []
  (:timeout-ms (backend-preflight-config)))

(defn- backend-version-timeout-ms []
  (:version-timeout-ms (backend-preflight-config)))

(defn- claude-preflight-args []
  (:claude-args (backend-preflight-config)))

(defn- executable-file?
  [path]
  (let [file (some-> path fs/file)]
    (and file
         (fs/exists? file)
         (not (fs/directory? file))
         (fs/executable? file))))

(defn- direct-command-path?
  [cmd]
  (boolean (re-find #"[\\/]" (or cmd ""))))

(defn- normalize-command-path
  [path]
  (when (executable-file? path)
    (str (-> path
             fs/path
             fs/absolutize))))

(defn- portable-command-path
  [cmd]
  (some-> cmd
          fs/which
          normalize-command-path))

(defn- path-entries
  []
  (str/split (or (System/getenv "PATH") "") #":"))

(defn- matching-command-path
  [entry cmd]
  (let [candidate (fs/path entry cmd)]
    (when (executable-file? candidate)
      (str candidate))))

(defn- resolve-cli-command-path
  [cmd]
  (cond
    (str/blank? cmd) nil
    (direct-command-path? cmd)
    (normalize-command-path cmd)

    :else
    (or (portable-command-path cmd)
        (some #(matching-command-path % cmd) (path-entries)))))

(defn- cli-process-env
  []
  (when (System/getenv "CLAUDECODE")
    (into {} (remove (fn [[k _]] (= k "CLAUDECODE"))) (System/getenv))))

(defn- run-cli-command
  [cmd timeout-ms & {:keys [workdir]}]
  (let [empty-stdin (java.io.ByteArrayInputStream. (byte-array 0))
        process (apply p/process
                       (cond-> {:out :string
                                :err :string
                                :continue true
                                :in empty-stdin}
                         (cli-process-env) (assoc :env (cli-process-env))
                         workdir (assoc :dir workdir))
                       cmd)
        result (deref process timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do
        (try
          (when-let [^Process jp (:proc process)]
            (.destroyForcibly jp))
          (catch Exception _ nil))
        {:out ""
         :err (messages/t :workflow-runner/cli-process-timeout
                          {:timeout-ms timeout-ms})
         :exit -1
         :timeout-ms timeout-ms})
      {:out (:out result)
       :err (:err result)
       :exit (:exit result)})))

(defn- success-response
  [output]
  (response/success output))

(defn- failure-response
  [category error-type message data]
  (-> (response/failure message {:data (assoc data :type error-type)})
      (assoc :anomaly (response/make-anomaly category message data))))

(defn- response-output
  [response]
  (merge (select-keys response [:content :exit-code :version])
         (or (:output response) {})))

(defn- response-succeeded?
  [response]
  (or (true? (:success response))
      (response/success? response)))

(defn- response-summary
  [response]
  (merge
   (select-keys response [:success :error :anomaly :exit-code])
   (select-keys (response-output response)
                [:content :exit-code :version])))

(defn- read-cli-version
  [cmd-path]
  (let [{:keys [out err exit timeout-ms]} (run-cli-command [cmd-path "--version"] (backend-version-timeout-ms))]
    (cond
      timeout-ms
      (failure-response :anomalies/unavailable
                        "backend_version_timeout"
                        (messages/t :workflow-runner/backend-version-timeout
                                    {:timeout-ms timeout-ms})
                        {:cmd-path cmd-path
                         :timeout-ms timeout-ms})

      (zero? exit)
      (if-let [version (or (some-> out str/trim not-empty)
                           (some-> err str/trim not-empty))]
        (success-response {:version version})
        (failure-response :anomalies/unavailable
                          "backend_version_empty_output"
                          (messages/t :workflow-runner/backend-version-empty)
                          {:cmd-path cmd-path
                           :exit-code exit}))

      :else
      (failure-response :anomalies/unavailable
                        "backend_version_cli_error"
                        (or (some-> err str/trim not-empty)
                            (some-> out str/trim not-empty)
                            (messages/t :workflow-runner/backend-version-exit
                                        {:exit-code exit}))
                        {:cmd-path cmd-path
                         :exit-code exit}))))

(defn- backend-stamp
  [llm-client]
  (when-let [backend (llm/client-backend llm-client)]
    (let [backend-config (get llm/backends backend)
          cmd (:cmd backend-config)
          cmd-path (when (:requires-cli? backend-config)
                     (resolve-cli-command-path cmd))]
      {:backend backend
       :backend-config backend-config
       :cmd cmd
       :cmd-path cmd-path})))

(defn- with-backend-version
  [stamp version]
  (assoc stamp :cmd-version version))

(defn- backend-provenance-lines
  [{:keys [backend cmd-path cmd-version]}]
  (concat
   [[:cyan (messages/t :workflow-runner/backend-label
                       {:backend (name backend)})]]
   (when cmd-path
     [[:cyan (messages/t :workflow-runner/backend-path {:path cmd-path})]])
   (when cmd-version
     [[:cyan (messages/t :workflow-runner/backend-version {:version cmd-version})]])))

(defn- print-backend-provenance!
  [quiet {:keys [backend cmd-path cmd-version]}]
  (print-colored-lines! quiet (backend-provenance-lines {:backend backend
                                                         :cmd-path cmd-path
                                                         :cmd-version cmd-version})))

(defn- claude-preflight-command
  [cmd-path]
  (into [cmd-path "-p" (backend-preflight-prompt)]
        (claude-preflight-args)))

(defn- cli-required-backend-stamp
  [llm-client]
  (when-let [stamp (backend-stamp llm-client)]
    (when (:requires-cli? (:backend-config stamp))
      stamp)))

(defn- parse-preflight-payload
  [content]
  (try
    (some-> content str/trim not-empty (json/parse-string true))
    (catch Exception _ nil)))

(defn- normalized-preflight-content
  [content]
  (loop [candidate (some-> content str/trim not-empty)]
    (let [parsed (parse-preflight-payload candidate)
          nested (or (some-> parsed :result str/trim not-empty)
                     (some-> parsed :content str/trim not-empty))]
      (if (and nested (not= nested candidate))
        (recur nested)
        candidate))))

(defn- preflight-success?
  [content]
  (= {:ok true} (parse-preflight-payload (normalized-preflight-content content))))

(defn- backend-stream-content
  [stream-parser output]
  (let [content (atom "")]
    (doseq [line (str/split-lines (or output ""))]
      (when-let [parsed (stream-parser line)]
        (when-let [delta (:delta parsed)]
          (swap! content str delta))))
    (some-> @content str/trim not-empty)))

(defn- decoded-preflight-content
  [backend-config output]
  (if-let [stream-parser (:stream-parser backend-config)]
    (backend-stream-content stream-parser output)
    (some-> output str/trim not-empty)))

(defn- generic-preflight-command
  [llm-client]
  (let [{:keys [backend model]} (:config llm-client)
        {:keys [cmd args-fn]} (get llm/backends backend)
        request (cond-> {:prompt (backend-preflight-prompt)}
                  model (assoc :model model))]
    (into [cmd] (args-fn request))))

(defn- run-generic-backend-preflight
  [llm-client cmd-path workdir]
  (let [{:keys [backend]} (:config llm-client)
        backend-config (get llm/backends backend)
        full-cmd (into [cmd-path] (rest (generic-preflight-command llm-client)))
        {:keys [out err exit timeout-ms]} (run-cli-command full-cmd
                                                           (backend-preflight-timeout-ms)
                                                           :workdir workdir)
        content (decoded-preflight-content backend-config out)]
    (cond
      timeout-ms
      (failure-response :anomalies/unavailable
                        "backend_preflight_timeout"
                        err
                        {:cmd-path cmd-path
                         :timeout-ms timeout-ms
                         :exit-code exit})

      (not (zero? exit))
      (failure-response :anomalies/unavailable
                        "backend_preflight_cli_error"
                        (or (some-> err str/trim not-empty)
                            content
                            (messages/t :workflow-runner/backend-preflight-exit
                                        {:exit-code exit}))
                        {:cmd-path cmd-path
                         :stdout (some-> out str/trim not-empty)
                         :stderr (some-> err str/trim not-empty)
                         :exit-code exit})

      (preflight-success? content)
      (success-response {:content content
                         :exit-code exit})

      :else
      (failure-response :anomalies/unavailable
                        "backend_preflight_unexpected_output"
                        (messages/t :workflow-runner/backend-preflight-failed
                                    {:backend (name backend)})
                        {:cmd-path cmd-path
                         :stdout (some-> out str/trim not-empty)
                         :stderr (some-> err str/trim not-empty)
                         :content (normalized-preflight-content content)
                         :exit-code exit}))))

(defn- run-claude-backend-preflight
  [cmd-path workdir]
  (let [{:keys [out err exit timeout-ms]} (run-cli-command (claude-preflight-command cmd-path)
                                                           (backend-preflight-timeout-ms)
                                                           :workdir workdir)
        trimmed (some-> out str/trim)
        content (normalized-preflight-content trimmed)]
    (cond
      timeout-ms
      (assoc (failure-response :anomalies/unavailable
                               "backend_preflight_timeout"
                               err
                               {:cmd-path cmd-path
                                :timeout-ms timeout-ms
                                :exit-code exit})
             :exit-code exit)

      (not (zero? exit))
      (assoc (failure-response :anomalies/unavailable
                               "backend_preflight_cli_error"
                               (or (some-> err str/trim not-empty)
                                   trimmed
                                   (messages/t :workflow-runner/backend-preflight-exit
                                               {:exit-code exit}))
                               {:cmd-path cmd-path
                                :exit-code exit})
             :exit-code exit)

      (preflight-success? content)
      (-> (success-response {:content content
                             :exit-code exit})
          (assoc :exit-code exit
                 :content content))

      :else
      (assoc (failure-response :anomalies/unavailable
                               "backend_preflight_unexpected_output"
                               (messages/t :workflow-runner/claude-preflight-unexpected-output)
                               {:cmd-path cmd-path
                                :stdout trimmed
                                :content content
                                :stderr (some-> err str/trim not-empty)
                                :exit-code exit})
             :exit-code exit))))

(defn- backend-cli-missing!
  [{:keys [backend cmd]}]
  (response/throw-anomaly! :anomalies/incorrect
                           (messages/t :workflow-runner/cli-not-on-path {:cmd cmd})
                           {:backend backend
                            :cmd cmd
                            :path (System/getenv "PATH")}))

(defn- backend-version-failed!
  [{:keys [backend cmd cmd-path]} version-response]
  (response/throw-anomaly! :anomalies/unavailable
                           (messages/t :workflow-runner/backend-version-failed
                                       {:backend (name backend)})
                           {:backend backend
                            :cmd cmd
                            :cmd-path cmd-path
                            :version-error (get-in version-response [:error :message])
                            :version-error-data (get-in version-response [:error :data])}))

(defn- backend-preflight-failed!
  [{:keys [backend cmd cmd-path cmd-version]} probe-response]
  (response/throw-anomaly! :anomalies/unavailable
                           (messages/t :workflow-runner/backend-preflight-failed
                                       {:backend (name backend)})
                           {:backend backend
                            :cmd cmd
                            :cmd-path cmd-path
                            :cmd-version cmd-version
                            :probe-response (response-summary probe-response)}))

(defn- run-backend-probe
  [llm-client {:keys [backend cmd-path]} workdir]
  (if (= backend :claude)
    (run-claude-backend-preflight cmd-path workdir)
    (run-generic-backend-preflight llm-client cmd-path workdir)))

(defn- ensure-cli-command-path!
  [stamp]
  (when-not (:cmd-path stamp)
    (backend-cli-missing! stamp))
  stamp)

(defn- versioned-backend-stamp
  [stamp]
  (let [version-response (read-cli-version (:cmd-path stamp))
        version (get-in (response-output version-response) [:version])]
    (when-not (response-succeeded? version-response)
      (backend-version-failed! stamp version-response))
    (with-backend-version stamp version)))

(defn- verify-backend-probe!
  [llm-client stamp workdir]
  (let [probe-response (run-backend-probe llm-client stamp workdir)]
    (when-not (response-succeeded? probe-response)
      (backend-preflight-failed! stamp probe-response))
    stamp))

(defn- run-backend-preflight!
  [quiet llm-client context]
  (when-let [stamp (cli-required-backend-stamp llm-client)]
    (let [stamp (-> stamp
                    ensure-cli-command-path!
                    versioned-backend-stamp)]
      (print-backend-provenance! quiet stamp)
      (verify-backend-probe! llm-client stamp (:worktree-path context)))))

(defn close-artifact-store [artifact-store]
  (when artifact-store
    (try
      (artifact/close-store artifact-store)
      (catch Exception _))))

(defn select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified.
   Checks :spec/workflow-type first, then :workflow/type as a fallback for
   specs that use the shorter key."
  [spec llm-client quiet]
  (if-let [explicit-type (or (:spec/workflow-type spec)
                             (:workflow/type spec))]
    (do
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/user-specified {:workflow-type (name explicit-type)}))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/auto-selected {:workflow-type (name (:workflow recommendation))})))
        (println (messages/t :workflow-runner/auto-selected-reason {:reasoning (:reasoning recommendation)}))
        (when (= :llm (:source recommendation))
          (println (messages/t :workflow-runner/auto-selected-confidence {:confidence (format "%.0f%%" (* 100 (:confidence recommendation 0.0)))})))
        (println (display/colorize :yellow (messages/t :workflow-runner/auto-selected-override))))
      (:workflow recommendation))))

(def ^:private workflow-aliases
  "Map legacy/alternate workflow type keywords to their canonical counterparts.
   Many work specs use :standard-sdlc but the only registered workflow is
   :canonical-sdlc."
  {:standard-sdlc :canonical-sdlc})

(defn resolve-workflow-alias
  "Resolve a workflow type through the alias map. Returns the canonical type
   if an alias exists, otherwise returns the type unchanged."
  [workflow-type]
  (get workflow-aliases workflow-type workflow-type))

(defn load-or-create-workflow [load-workflow workflow-type workflow-version]
  (let [workflow-type (resolve-workflow-alias workflow-type)]
    (try
      (load-and-validate-workflow load-workflow workflow-type workflow-version)
      (catch Exception e
        (case workflow-type
          :test-only
          {:workflow/id :test-only
           :workflow/version "inline"
           :workflow/name "Test Generation"
           :workflow/pipeline [{:phase :verify} {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 10}}

          :comment-fix
          {:workflow/id :comment-fix
           :workflow/version "inline"
           :workflow/name "Comment Fix"
           :workflow/pipeline [{:phase :implement :gates [:syntax :lint :no-secrets]}
                               {:phase :done}]
           :workflow/config {:max-tokens 20000 :max-iterations 5}}

          (throw e))))))

(defn execute-workflow-pipeline [run-pipeline workflow input callbacks artifact-store event-stream]
  (-> callbacks
      (cond-> artifact-store (assoc :artifact-store artifact-store))
      (cond-> event-stream (assoc :event-stream event-stream))
      (->> (run-pipeline workflow input))))

(defn- failure-message
  "Build a meaningful failure message from a workflow result.
   Falls back to execution status when no explicit errors exist."
  [result]
  (let [errors (:execution/errors result)
        status (get result :execution/status :unknown)]
    (if (seq errors)
      (str (first errors))
      (str "Workflow ended with status: " (name status)))))

(defn publish-completion-event [event-stream workflow-id result]
  (let [status (if (phase/succeeded? result) :success :failure)
        duration-ms (get-in result [:execution/metrics :duration-ms])]
    (es/publish! event-stream
                 (if (= status :success)
                   (es/workflow-completed event-stream workflow-id status duration-ms)
                   (es/workflow-failed event-stream workflow-id
                                       {:message (failure-message result)
                                        :errors (or (seq (:execution/errors result))
                                                    [{:type :unknown-failure
                                                      :message (failure-message result)}])})))))

;------------------------------------------------------------------------------ Layer 1
;; Execution orchestration

(defn- publish-failure-event!
  "Publish a workflow failure event, swallowing exceptions."
  [event-stream workflow-id error-type message]
  (try
    (es/publish! event-stream
                 (es/workflow-failed event-stream workflow-id
                                     {:message message
                                      :errors [{:type error-type :message message}]}))
    (catch Exception _ nil)))

(defn execute-with-events [{:keys [run-pipeline workflow workflow-input context artifact-store
                                     event-stream workflow-id sandbox-cleanup opts]}]
  (let [completed? (atom false)]
    (try+
      (if-let [sandbox-error (:sandbox-error context)]
        (let [result {:success? false
                      :errors [{:type :sandbox-setup-failed
                                :message (str (:error sandbox-error))}]}]
          (publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (display/print-result result opts)
          result)
        (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input context artifact-store event-stream)]
          (publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (close-artifact-store artifact-store)
          (display/print-result result opts)
          result))
      (catch Object _
        (let [e (:throwable &throw-context)]
          (when-not @completed?
            (publish-failure-event! event-stream workflow-id :interrupted
                                   (messages/t :workflow-runner/stopped {:error (ex-message e)}))
            (reset! completed? true))
          (throw e)))
      (finally
        (when-not @completed?
          (publish-failure-event! event-stream workflow-id :cancelled
                                 (messages/t :workflow-runner/cancelled)))
        (when sandbox-cleanup
          (sandbox-cleanup)
          (when-not (:quiet opts)
            (println (display/colorize :yellow (messages/t :workflow-runner/sandbox-released)))))))))

(defn run-workflow! [workflow-id {:keys [version output quiet event-stream dashboard-url]
                                    :or {version "latest" output :pretty quiet false}
                                    :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create event stream if not provided (dashboard-url takes precedence)
          es (or event-stream
                 (when-not dashboard-url
                   (try
                     (require '[ai.miniforge.event-stream.interface :as es])
                     (when-let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
                       (when-let [create-fn (ns-resolve es-ns 'create-event-stream)]
                         (create-fn)))
                     (catch Exception _ nil))))]
      (display/print-workflow-header workflow-id version quiet)
      (let [workflow-input (context/resolve-input opts)
            workflow (load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)
            ;; Pass dashboard-url in callbacks if provided
            callbacks-with-url (cond-> callbacks
                                 dashboard-url (assoc :dashboard-url dashboard-url))
            progress-cleanup (display/start-progress! es quiet)]
        (try
          (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store es)]
            (close-artifact-store artifact-store)
            (display/print-result result opts)
            result)
          (finally
            (progress-cleanup)))))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (messages/t :workflow-runner/run-error {:error (ex-message e)}))))
      (when (= output :json)
        (println (json/generate-string
                  {:status "error"
                   :error (ex-message e)
                   :data (ex-data e)}
                  {:pretty true})))
      (throw e))))

(defn format-workflow-listing [workflows]
  (if (empty? workflows)
    (println (messages/t :workflow-runner/no-workflows))
    (do
      (println (display/colorize :cyan (messages/t :workflow-runner/available-workflows)))
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (doseq [{:workflow/keys [id version description type]} workflows]
        (println (str (display/colorize :bold (str "  " (name id)))
                      " (v" version ")"
                      "  " (display/colorize :yellow (messages/t :workflow-runner/workflow-type-label {:type (or type :unknown)}))
                      (when description (str "\n    " description))))
        (println))
      (println (display/colorize :cyan (apply str (repeat 60 "─")))))))

(defn list-workflows-from-resources []
  (try
    (let [list-workflows workflow/list-workflows]
      (->> (list-workflows)
           (sort-by (juxt :workflow/id :workflow/version))
           format-workflow-listing))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)}))))))

(defn list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)})))
      (throw e))))

;------------------------------------------------------------------------------ Layer 2
;; Spec-driven execution

(defn run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  (try+
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          backend-override (:backend opts)
          selection-llm-client (context/create-llm-client nil spec quiet backend-override)
          workflow-type (select-workflow-type spec selection-llm-client quiet)
          workflow-version (get spec :spec/workflow-version "latest")
          workflow (load-or-create-workflow load-workflow workflow-type workflow-version)
          enriched-spec (context/decorate-spec-with-runtime-context spec opts)
          ;; Infer repo URL and branch for execution environment (Docker clone / worktree).
          ;; Reuses sandbox helpers which fall back to `git remote get-url origin`.
          repo-url (sandbox/infer-repo-url spec enriched-spec)
          ;; In governed mode, always clone main — the capsule creates a fresh
          ;; working copy, not a checkout of the host worktree's branch.
          branch   (if (= :governed (:execution-mode opts))
                     (or (:spec/branch spec) "main")
                     (sandbox/infer-branch spec enriched-spec))
          workflow-input (context/spec->workflow-input enriched-spec)
          artifact-store (create-artifact-store quiet)
          event-stream (es/create-event-stream)
          _supervisor (supervisory/attach! event-stream)
          workflow-id (or (get-in enriched-spec [:spec/metadata :session-id]) (random-uuid))
          ;; Control state for dashboard commands (pause/resume/stop)
          control-state (es/create-control-state)
          command-poller-cleanup (dashboard/start-command-poller! workflow-id control-state)
          ;; Create workflow-specific LLM client for execution
          llm-client (context/create-llm-client workflow spec quiet backend-override)
          callbacks (create-phase-callbacks quiet)
          base-context (let [ctx (context/create-workflow-context
                             {:callbacks callbacks
                              :artifact-store artifact-store
                              :event-stream event-stream
                              :workflow-id workflow-id
                              :workflow-type workflow-type
                              :workflow-version workflow-version
                              :llm-client llm-client
                              :quiet quiet
                              :spec-title (:spec/title spec)
                              :control-state control-state
                              :skip-lifecycle-events true
                              :execution-opts (:execution-opts opts)
                              :source-dir (:spec/source-dir spec)})]
                        ;; Assoc repo-url, branch, and (optionally) execution-mode
                        ;; so runner.clj can clone into Docker or create a worktree.
                        (cond-> (assoc ctx
                                       :repo-url repo-url
                                       :branch branch
                                       :execution-mode (get opts :execution-mode :local))
))
          sandbox? (or (:sandbox opts) (:spec/sandbox spec))
          [context sandbox-cleanup] (sandbox/setup-sandbox-context base-context sandbox? spec enriched-spec quiet)
          progress-cleanup (display/start-progress! event-stream quiet)]
      (when-not quiet
        (display/print-workflow-header (keyword (str "adhoc-" (hash spec))) "adhoc" quiet))
      (dashboard/print-dashboard-status! quiet)
      (assert-runtime-alignment! spec context)
      (print-runtime-provenance! quiet context)
      (run-backend-preflight! quiet llm-client context)
      (let [provenance (move-spec-to-in-progress! (:spec/provenance enriched-spec))]
        (try
          (let [result (execute-with-events {:run-pipeline run-pipeline
                                             :workflow workflow
                                             :workflow-input workflow-input
                                             :context context
                                             :artifact-store artifact-store
                                             :event-stream event-stream
                                             :workflow-id workflow-id
                                             :sandbox-cleanup sandbox-cleanup
                                             :opts opts})
                outcome-status (if (phase/succeeded? result) :completed :failed)]
            (move-spec-on-completion! provenance result)
            ;; Trigger meta-loop learning cycle in background
            (trigger-meta-loop-after-workflow! workflow-id outcome-status nil)
            result)
          (finally
            (progress-cleanup)
            (when command-poller-cleanup (command-poller-cleanup))))))
    (catch Object _
      (let [e (:throwable &throw-context)]
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/spec-execution-failed {:error (ex-message e)})))
          (flush))
        (throw e)))))

;------------------------------------------------------------------------------ Layer 2b
;; Resume workflow from checkpointed DAG state

(defn resume-workflow-from-spec!
  "Resume a previously failed or paused workflow from checkpointed DAG state.

   Arguments:
   - workflow-id: UUID string of the workflow to resume
   - spec: The original spec map (same spec file used for the initial run)
   - opts: Same opts as run-workflow-from-spec!"
  [workflow-id-str spec {:keys [quiet] :or {quiet false} :as opts}]
  (let [resume-ctx (try
                     (let [resume-fn (requiring-resolve
                                      'ai.miniforge.workflow.dag-resilience/resume-context)]
                       (resume-fn workflow-id-str))
                     (catch Exception e
                       (when-not quiet
                         (println (display/colorize :red
                                   (messages/t :workflow-runner/resume-state-failed {:error (ex-message e)}))))
                       nil))]
    (when-not quiet
      (println (display/colorize :cyan
                (messages/t :workflow-runner/resuming {:workflow-id workflow-id-str})))
      (println (display/colorize :cyan
                (messages/t :workflow-runner/previously-completed {:count (count (:pre-completed-ids resume-ctx))})))
      (when (seq (:pre-completed-artifacts resume-ctx))
        (println (display/colorize :cyan
                  (messages/t :workflow-runner/recovered-artifacts {:count (count (:pre-completed-artifacts resume-ctx))})))))
    (if (and resume-ctx (seq (:pre-completed-ids resume-ctx)))
      ;; Re-run with pre-completed task IDs injected
      (let [execution-opts (-> (get opts :execution-opts {})
                               (assoc :pre-completed-dag-tasks
                                      (:pre-completed-ids resume-ctx))
                               (assoc :pre-completed-artifacts
                                      (:pre-completed-artifacts resume-ctx)))
            opts-with-resume (assoc opts :execution-opts execution-opts)]
        (run-workflow-from-spec! spec opts-with-resume))
      (do
        (when-not quiet
          (println (display/colorize :yellow
                    (messages/t :workflow-runner/no-completed-tasks))))
        (run-workflow-from-spec! spec opts)))))

;------------------------------------------------------------------------------ Layer 3
;; Chain-driven execution

(defn resolve-chain-input
  "Resolve chain input from a spec file path or inline JSON."
  [opts]
  (let [spec-path (:spec opts)
        inline-json (:input-json opts)]
    (cond
      inline-json (json/parse-string inline-json true)
      spec-path (let [parsed (edn/read-string (slurp spec-path))
                      enriched (context/decorate-spec-with-runtime-context parsed {})]
                  (context/spec->workflow-input enriched))
      :else {})))

(defn print-chain-header
  "Print chain execution banner."
  [chain-id chain-def quiet]
  (when-not quiet
    (println)
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-header {:chain-id (name chain-id)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-description {:description (:chain/description chain-def)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-steps {:count (count (:chain/steps chain-def))})))
    (println (display/colorize :cyan (apply str (repeat 60 "─"))))))

(defn print-chain-result
  "Print chain execution result summary."
  [result quiet]
  (when-not quiet
    (let [steps (:chain/step-results result)
          duration (:chain/duration-ms result)]
      (println)
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (if (phase/succeeded? result)
        (println (display/colorize :green (messages/t :workflow-runner/chain-completed {:count (count steps) :duration duration})))
        (let [failed-step (some #(when (phase/failed? %) (:step/id %)) steps)]
          (println (display/colorize :red (messages/t :workflow-runner/chain-failed-at {:step (when failed-step (name failed-step))}))))))))

(defn run-chain!
  "Execute a chain of workflows.

   Arguments:
   - chain-id: Chain identifier keyword (e.g. :reporting-chain)
   - opts: {:version \"latest\" :spec \"spec.edn\" :input-json \"{...}\" :quiet false}"
  [chain-id opts]
  (let [quiet (get opts :quiet false)
        version (get opts :version "latest")]
    (try
      (let [chain-result (workflow/load-chain chain-id version)
            chain-def (:chain chain-result)
            chain-input (resolve-chain-input opts)
            event-stream (es/create-event-stream)
            _supervisor (supervisory/attach! event-stream)
            llm-client (context/create-llm-client nil nil quiet)
            callbacks (create-phase-callbacks quiet)
            context (context/create-workflow-context {:callbacks callbacks
                                                      :event-stream event-stream
                                                      :llm-client llm-client
                                                      :quiet quiet
                                                      :workflow-id (random-uuid)
                                                      :workflow-type chain-id
                                                      :workflow-version version
                                                      :spec-title (str "Chain: " (name chain-id))
                                                      :control-state (es/create-control-state)})
            progress-cleanup (display/start-progress! event-stream quiet)]
        (print-chain-header chain-id chain-def quiet)
        (dashboard/print-dashboard-status! quiet)
        (run-backend-preflight! quiet llm-client context)
        (try
          (let [result (workflow/run-chain chain-def chain-input context)]
            (print-chain-result result quiet)
            result)
          (finally
            (progress-cleanup))))
      (catch Exception e
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/chain-execution-failed {:error (ex-message e)}))))
        (throw e)))))

(defn list-chains!
  "List all available chain definitions."
  []
  (try
    (let [chains (workflow/list-chains)]
      (if (empty? chains)
        (println (messages/t :workflow-runner/no-chains))
        (do
          (println (display/colorize :cyan (messages/t :workflow-runner/available-chains)))
          (println (display/colorize :cyan (apply str (repeat 60 "─"))))
          (doseq [{:keys [id version description steps]} chains]
            (println (str (display/colorize :bold (str "  " (name id)))
                          " (v" version ")"
                          "  " (messages/t :workflow-runner/chain-steps-label {:steps steps})))
            (when description
              (println (str "    " description)))
            (println))
          (println (display/colorize :cyan (apply str (repeat 60 "─")))))))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-chains-failed {:error (ex-message e)})))
      (throw e))))
