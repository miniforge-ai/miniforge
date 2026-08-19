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
(ns ai.miniforge.cli.workflow-runner.context
  "Workflow input resolution and runtime context creation. Git checkout
   state lives in `ai.miniforge.cli.workflow-runner.context-git`."
  (:require
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.cli.workflow-runner.context-git :as git]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.tenancy.interface :as tenancy]))

;------------------------------------------------------------------------------ Layer 0

;; Input resolution
(defn ^{:stratum 0} read-input-file [path]
  (when path
    (let [file (fs/file path)]
      (when-not (fs/exists? file)
        (response/throw-anomaly! :anomalies/not-found
                                (str "Input file not found: " path)
                                {:path path}))
      (let [content (slurp file)
            ext (fs/extension file)]
        (case ext
          "edn" (edn/read-string content)
          "json" (json/parse-string content true)
          (response/throw-anomaly! :anomalies/unsupported
                                  (str "Unsupported file format: " ext " (use .edn or .json)")
                                  {:path path :extension ext}))))))

(defn ^{:stratum 0} parse-inline-json [s]
  (when s
    (try
      (json/parse-string s true)
      (catch Exception e
        (response/throw-anomaly! :anomalies/fault
                                (str "Failed to parse input JSON: " (ex-message e))
                                {:input s})))))

(defn- ^{:stratum 0} execution-worktree-path
  [execution-opts]
  (get execution-opts :worktree-path))

(defn- ^{:stratum 0} source-root-path
  [source-dir]
  (or (worktree/worktree-root source-dir)
      source-dir
      (worktree/worktree-root)
      (System/getProperty "user.dir")))

(defn ^{:stratum 0} get-files-in-scope
  "Resolve scope paths to actual file paths.

   Handles both individual files and directories. Directories are expanded
   to their contained source files (*.clj, *.cljc, *.cljs, *.edn)."
  [intent]
  (->> (get intent :scope [])
       (mapcat (fn [path]
                 (try
                   (cond
                     (not (fs/exists? path))
                     [path] ;; Keep non-existent paths for error reporting

                     (fs/directory? path)
                     (->> (fs/glob path "**.{clj,cljc,cljs,edn}")
                          (map str)
                          vec)

                     :else [path])
                   (catch Exception _ [path]))))
       vec))

(defn ^{:stratum 0} spec->workflow-input [enriched-spec]
  (merge (:spec/raw-data enriched-spec)
         {:title (:spec/title enriched-spec)
          :description (:spec/description enriched-spec)
          :intent (:spec/intent enriched-spec)
          :constraints (:spec/constraints enriched-spec)
          :acceptance-criteria (:spec/acceptance-criteria enriched-spec)
          :code-artifact (:spec/code-artifact enriched-spec)
          :plan-tasks (:spec/plan-tasks enriched-spec)
          :repo-url (:spec/repo-url enriched-spec)
          :branch (:spec/branch enriched-spec)
          :llm-backend (:spec/llm-backend enriched-spec)
          :sandbox (:spec/sandbox enriched-spec)
          :context (:spec/context enriched-spec)
          :metadata (:spec/metadata enriched-spec)
          :provenance (:spec/provenance enriched-spec)
          ;; Spec source path → PR provenance frontmatter (deterministic
          ;; PR → spec mapping).
          :spec/path (:spec/path enriched-spec)}))

(defn ^{:stratum 0} resolve-acting
  "Resolve who this run acts for, or nil when no operator is configured.

   Called once per run, at the boundary that starts it. Everything
   downstream reads `:execution/acting` off the context rather than
   resolving again — two resolutions are two answers about who acted.

   RETURNS NIL RATHER THAN REFUSING, for now. Nothing configures an
   operator yet, so requiring one here would fail every run in existence
   until `[:tenancy :operator-name]` is set. The refusal still belongs
   in the system, just not at this end of it: Ariadne step 3c stamps
   owners onto records, and that is where an absent identity has
   something real to protect and will hard-fail.

   What this does NOT do is invent one. A default tenant here would be
   indistinguishable later from a real operator, and every record
   created under it would carry an owner that looks observed and is
   fabricated. Carrying no answer is recoverable; carrying a plausible
   wrong one is not."
  []
  (let [identity (tenancy/resolve-operator (config/load-config))]
    (when-not (anomaly/anomaly? identity)
      (tenancy/establish-acting identity (java.time.Instant/now)))))

(defn ^{:stratum 0} create-llm-client
  ([workflow spec quiet] (create-llm-client workflow spec quiet nil))
  ([workflow spec quiet backend-override]
   (try
     (let [cfg (config/load-config)
           llm-backend (config/get-llm-backend
                        cfg
                            (or backend-override
                                (get-in workflow [:workflow/config :llm-backend])
                                (:spec/llm-backend spec)))]
       (llm/create-client {:backend llm-backend}))
     (catch Exception e
       (when-not quiet
         (println (display/colorize :yellow (str "Warning: Could not create LLM client (" (ex-message e) "), agents will use fallback mode"))))
       nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-input [{:keys [input input-json]}]
  (cond
    input-json (parse-inline-json input-json)
    input (read-input-file input)
    :else {}))

;; Context decoration
(defn ^{:stratum 1} decorate-spec-with-runtime-context [spec {:keys [iteration parent-task-id] :or {iteration 1}}]
  (let [cwd (or (worktree/worktree-root) (str (fs/cwd)))
        git-info (git/get-git-info cwd)
        files-in-scope (get-files-in-scope (:spec/intent spec))]
    (assoc spec
           :spec/context
           (cond-> {:cwd cwd
                    :files-in-scope files-in-scope
                    :environment :development}
             git-info (merge git-info))

           :spec/metadata
           (cond-> {:submitted-at (java.util.Date.)
                    :session-id (random-uuid)
                    :iteration iteration}
             parent-task-id (assoc :parent-task-id parent-task-id)))))

;; Workflow context assembly
(defn ^{:stratum 1} create-workflow-context [{:keys [callbacks artifact-store event-stream workflow-id
                                       workflow-type workflow-version llm-client quiet
                                       spec-title control-state skip-lifecycle-events
                                       execution-opts source-dir
                                       routing-trigger-event-id acting]}]
  (let [on-chunk (es/create-streaming-callback event-stream workflow-id :agent
                                               {:print? (not quiet) :quiet? quiet})
        acting (or acting (resolve-acting))
        source-root (source-root-path source-dir)
        git-info (git/get-git-state source-root)
        worktree-path (or (execution-worktree-path execution-opts)
                          (worktree/worktree-root)
                          (System/getProperty "user.dir"))]
    (es/publish! event-stream
                 (es/workflow-started event-stream workflow-id
                                      {:name (or spec-title (name workflow-type))
                                       :version workflow-version}
                                      ;; N15-4: thread the routing-trigger-event-id when the
                                      ;; caller (an external listener invoking the CLI, a
                                      ;; primer-driven resume, an in-process dispatcher) named
                                      ;; the routing trigger that fired this workflow.
                                      {:routing/trigger-event-id routing-trigger-event-id}))
    (cond-> callbacks
      llm-client (assoc :llm-backend llm-client)
      artifact-store (assoc :artifact-store artifact-store)
      on-chunk (assoc :on-chunk on-chunk)
      event-stream (assoc :event-stream event-stream)
      control-state (assoc :control-state control-state)
      skip-lifecycle-events (assoc :skip-lifecycle-events true)
      execution-opts (assoc :execution/opts execution-opts)
      source-root (assoc :source-root source-root)
      git-info (merge git-info)
      ;; Resolved once, here, at the run boundary. `create-context`
      ;; lifts this onto :execution/acting, which is persisted and
      ;; restored from the snapshot so a resume cannot reattribute the
      ;; run to whoever resumed it. Absent when no operator is
      ;; configured — see `resolve-acting`.
      acting (assoc :acting acting)
      true (assoc :worktree-path worktree-path))))
