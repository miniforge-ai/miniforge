(ns ai.miniforge.cli.workflow-runner.context
  "Workflow input resolution and runtime context creation."
  (:require
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Input resolution

(defn read-input-file [path]
  (when path
    (let [file (fs/file path)]
      (when-not (fs/exists? file)
        (throw (ex-info (str "Input file not found: " path) {:path path})))
      (let [content (slurp file)
            ext (fs/extension file)]
        (case ext
          "edn" (edn/read-string content)
          "json" (json/parse-string content true)
          (throw (ex-info (str "Unsupported file format: " ext " (use .edn or .json)")
                          {:path path :extension ext})))))))

(defn parse-inline-json [s]
  (when s
    (try
      (json/parse-string s true)
      (catch Exception e
        (throw (ex-info (str "Failed to parse input JSON: " (ex-message e))
                        {:input s} e))))))

(defn resolve-input [{:keys [input input-json]}]
  (cond
    input-json (parse-inline-json input-json)
    input (read-input-file input)
    :else {}))

(defn- get-git-info []
  (try
    (let [branch-result (p/shell {:out :string :err :string :continue true}
                                 "git" "rev-parse" "--abbrev-ref" "HEAD")
          commit-result (p/shell {:out :string :err :string :continue true}
                                 "git" "rev-parse" "--short" "HEAD")]
      (when (and (zero? (:exit branch-result))
                 (zero? (:exit commit-result)))
        {:git-branch (clojure.string/trim (:out branch-result))
         :git-commit (clojure.string/trim (:out commit-result))}))
    (catch Exception _ nil)))

(defn- get-files-in-scope [intent]
  (->> (get intent :scope [])
       (mapcat (fn [path]
                 (try
                   (if (and (fs/exists? path) (not (fs/directory? path)))
                     [path]
                     [path])
                   (catch Exception _ [path]))))
       vec))

(defn spec->workflow-input [enriched-spec]
  (merge (:spec/raw-data enriched-spec)
         {:title (:spec/title enriched-spec)
          :description (:spec/description enriched-spec)
          :intent (:spec/intent enriched-spec)
          :constraints (:spec/constraints enriched-spec)
          :context (:spec/context enriched-spec)
          :metadata (:spec/metadata enriched-spec)
          :provenance (:spec/provenance enriched-spec)}))

;------------------------------------------------------------------------------ Layer 1
;; Context decoration

(defn decorate-spec-with-runtime-context [spec {:keys [iteration parent-task-id] :or {iteration 1}}]
  (let [git-info (get-git-info)
        files-in-scope (get-files-in-scope (:spec/intent spec))]
    (assoc spec
           :spec/context
           (cond-> {:cwd (str (fs/cwd))
                    :files-in-scope files-in-scope
                    :environment :development}
             git-info (merge git-info))

           :spec/metadata
           (cond-> {:submitted-at (java.util.Date.)
                    :session-id (random-uuid)
                    :iteration iteration}
             parent-task-id (assoc :parent-task-id parent-task-id)))))

(defn create-llm-client [workflow spec quiet]
  (try
    (let [cfg (config/load-config)
          llm-backend (config/get-llm-backend
                       cfg
                       (or (get-in workflow [:workflow/config :llm-backend])
                           (get-in spec [:spec/raw-data :llm-backend])))]
      (when-let [create-client (requiring-resolve 'ai.miniforge.llm.interface/create-client)]
        (create-client {:backend llm-backend})))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :yellow (str "Warning: Could not create LLM client (" (ex-message e) "), agents will use fallback mode"))))
      nil)))

;------------------------------------------------------------------------------ Layer 2
;; Workflow context assembly

(defn create-workflow-context [{:keys [callbacks artifact-store event-stream workflow-id
                                       workflow-type workflow-version llm-client quiet
                                       spec-title control-state skip-lifecycle-events]}]
  (let [on-chunk (es/create-streaming-callback event-stream workflow-id :agent
                                                {:print? (not quiet) :quiet? quiet})]
    (es/publish! event-stream
                 (es/workflow-started event-stream workflow-id
                                      {:name (or spec-title (name workflow-type))
                                       :version workflow-version}))
    (cond-> callbacks
      llm-client (assoc :llm-backend llm-client)
      artifact-store (assoc :artifact-store artifact-store)
      on-chunk (assoc :on-chunk on-chunk)
      event-stream (assoc :event-stream event-stream)
      control-state (assoc :control-state control-state)
      skip-lifecycle-events (assoc :skip-lifecycle-events true)
      true (assoc :worktree-path (System/getProperty "user.dir")))))
