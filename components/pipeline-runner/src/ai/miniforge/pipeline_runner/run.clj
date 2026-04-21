(ns ai.miniforge.pipeline-runner.run
  (:require [ai.miniforge.pipeline.interface :as pipeline]
            [ai.miniforge.connector.interface :as conn]
            [ai.miniforge.data-quality.interface :as dq]
            [ai.miniforge.pipeline-runner.core :as core]
            [ai.miniforge.pipeline-runner.messages :as msg]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.time Instant]))

(def default-config
  "Default execution configuration. Override via stage :config map."
  {:connector/page-size  1000
   :publish/default-mode :append})

(defn- stage-result
  "Build a stage result map. Merges extras onto the base."
  [id name status extras]
  (merge {:stage/id id :stage/name name :status status :retry-count 0}
         extras))

(defn- timestamps
  "Build the {:started-at :completed-at} window for a just-completed stage.
   started-at defaults to now (transform/validate stages); pass a value
   from context to honor an existing window (ingest stage)."
  ([] (timestamps nil))
  ([started-at]
   (let [now (Instant/now)]
     {:started-at   (or started-at now)
      :completed-at now})))

(def ^:private auth-keys
  "Keys that belong to auth config, extracted from stage config."
  #{:auth/method :auth/credential-id :auth/credential-scope
    :auth/client-id :auth/token-endpoint :auth/certificate-path :auth/vault-path})

(defn- extract-auth
  "Extract auth keys from a config map into a separate auth map."
  [config]
  (not-empty (select-keys config auth-keys)))

(defn- resolve-schema-name
  "Resolve the schema-name for a connector extract call.
   Connectors use a resource key (e.g. \"issues\", \"pulls\") not the stage name.
   Check for common resource config keys, falling back to the stage name."
  [config stage-name]
  (or (:github/resource config)
      (:gitlab/resource config)
      (:resource config)
      stage-name))

(defn- extract-cursor-map
  [context]
  (or (:pipeline-run/connector-cursors context)
      (:connector-cursors context)
      {}))

(defn- cursor-entry->cursor
  [entry]
  (cond
    (nil? entry) nil
    (:cursor entry) (:cursor entry)
    (:cursor/type entry) entry
    :else nil))

(defn- prior-cursor
  [{:stage/keys [id connector-ref config name]} context]
  (let [schema-name (resolve-schema-name config name)
        cursor-map  (extract-cursor-map context)]
    (or (some-> (get cursor-map id) cursor-entry->cursor)
        (some-> (get cursor-map [connector-ref schema-name]) cursor-entry->cursor)
        (some-> (get cursor-map connector-ref) cursor-entry->cursor))))

(defn- cursor-entry
  [{:stage/keys [id name connector-ref]} schema-name cursor]
  {:stage/id            id
   :stage/name          name
   :stage/connector-ref connector-ref
   :stage/schema-name   schema-name
   :cursor              cursor
   :cursor/updated-at   (Instant/now)})

;;------------------------------------------------------------------------------ Stage executors

(defn- execute-ingest-stage
  "Execute an ingest stage using the connector's extract method."
  [{:stage/keys [id name connector-ref config]} connectors context]
  (let [connector (get connectors connector-ref)]
    (if (nil? connector)
      (stage-result id name :failed
                    {:error-message (msg/t :run/connector-not-found {:ref connector-ref})})
      (try
        (let [auth   (or (extract-auth config) (get context :auth {}))
              schema (resolve-schema-name config name)
              cursor (prior-cursor {:stage/id id
                                    :stage/name name
                                    :stage/connector-ref connector-ref
                                    :stage/config config}
                                   context)
              handle (:connection/handle (conn/connect connector (or config {}) auth))
              result (conn/extract connector handle schema
                                   (cond-> {:extract/batch-size (get config :connector/page-size
                                                                     (:connector/page-size default-config))}
                                     cursor (assoc :extract/cursor cursor)))]
          (conn/close connector handle)
          (stage-result id name :completed
                        (merge (timestamps (get context :started-at))
                               {:schema-name schema
                                :records (:records result)
                                :cursor (:extract/cursor result)})))
        (catch Exception e
          (stage-result id name :failed {:error-message (.getMessage e)}))))))

(defn- execute-publish-stage
  "Execute a publish stage using the connector's publish method."
  [{:stage/keys [id name connector-ref config]} connectors context input-records]
  (let [connector (get connectors connector-ref)]
    (if (nil? connector)
      (stage-result id name :failed
                    {:error-message (msg/t :run/connector-not-found {:ref connector-ref})})
      (try
        (let [auth   (or (extract-auth config) (:auth context {}))
              handle (:connection/handle (conn/connect connector (or config {}) auth))
              result (conn/publish connector handle name input-records
                                   {:publish/mode     (get config :publish-mode
                                                          (:publish/default-mode default-config))
                                    :publish/datasets (:publish/datasets context)})]
          (conn/close connector handle)
          (stage-result id name :completed {:completed-at (Instant/now)}))
        (catch Exception e
          (stage-result id name :failed {:error-message (.getMessage e)}))))))

(defn- execute-transform-stage
  "Execute a non-connector stage (normalize, transform, aggregate, etc.).
   If :stage/transform config with :transform/type is present and a matching
   transform fn exists in context :transforms, calls it with input records.
   Otherwise passes records through unchanged."
  [{:stage/keys [id name config]} context input-records]
  (let [transform-type (get-in config [:stage/transform :transform/type])
        transform-fn   (when transform-type
                         (get-in context [:transforms transform-type]))]
    (if transform-fn
      (try
        (let [result-records (transform-fn input-records config)]
          (stage-result id name :completed
                        (assoc (timestamps) :records result-records)))
        (catch Exception e
          (stage-result id name :failed {:error-message (.getMessage e)})))
      (stage-result id name :completed
                    (assoc (timestamps) :records input-records)))))

(defn- execute-validate-stage
  "Execute a validate stage using data-quality rules.
   If :stage/quality-pack is present in config, evaluates records and filters.
   Otherwise falls through to transform (pass-through)."
  [{:stage/keys [id name config]} context input-records]
  (if-let [quality-pack (:stage/quality-pack config)]
    (let [evaluation (dq/evaluate-records (:pack/rules quality-pack) input-records)
          passed     (dq/filter-passed evaluation)
          report     (dq/generate-report (:pack/id quality-pack) evaluation)]
      (stage-result id name :completed
                    (merge (timestamps)
                           {:records passed
                            :quality-report report})))
    (execute-transform-stage {:stage/id id :stage/name name :stage/config config} context input-records)))

;;------------------------------------------------------------------------------ DAG scheduling helpers

(defn- skip-remaining-tasks!
  "Skip all still-pending tasks. Called when any stage fails to halt execution."
  [run-atom]
  (doseq [[task-id task] (:run/tasks @run-atom)
          :when (= :pending (:task/status task))]
    (dag/transition-task! run-atom task-id :skipped nil)))

(defn- collect-inputs
  "Collect input datasets and flattened records produced by upstream stages."
  [stage-def stage-map stage-outputs]
  (let [dep->dataset (fn [dep-id]
                       (when-let [recs (get stage-outputs dep-id)]
                         [(get-in stage-map [dep-id :stage/name]) recs]))
        datasets    (into {} (keep dep->dataset) (:stage/dependencies stage-def))]
    {:datasets datasets
     :records  (vec (mapcat val datasets))}))

(defn- run-stage
  "Dispatch to the appropriate stage executor based on :stage/family."
  [stage-def connectors context input-datasets input-records]
  (case (:stage/family stage-def)
    :ingest   (execute-ingest-stage stage-def connectors context)
    :publish  (execute-publish-stage stage-def connectors
                                     (assoc context :publish/datasets input-datasets)
                                     input-records)
    :validate (execute-validate-stage stage-def context input-records)
    (execute-transform-stage stage-def context input-records)))

(defn- apply-stage-result!
  "Update run-atom based on stage result. Skips remaining tasks on failure."
  [run-atom stage-id result]
  (if (= :failed (:status result))
    (do
      (dag/mark-failed! run-atom stage-id {:reason (:error-message result)} nil)
      (skip-remaining-tasks! run-atom))
    (dag/mark-completed! run-atom stage-id nil)))

;;------------------------------------------------------------------------------ Spawn support

(defn- generate-spawns
  "If the stage config contains :stage/spawn-fn, look up the function in
   context [:spawn-fns] and call it with (records config) to produce spawn requests.
   Returns a seq of spawn-request maps, or nil."
  [stage-def context records]
  (when-let [spawn-key (get-in stage-def [:stage/config :stage/spawn-fn])]
    (when-let [spawn-fn (get-in context [:spawn-fns spawn-key])]
      (seq (spawn-fn records (:stage/config stage-def))))))

(defn- spawn->stage-def
  "Convert a spawn-request map into a stage-def suitable for the stage-map.
   Assigns a new stage id and wires the parent as a dependency.
   Inherits :stage/connector-ref from parent when the spawn omits it."
  [spawn parent-stage-def]
  (let [parent-id (:stage/id parent-stage-def)]
    {:stage/id            (random-uuid)
     :stage/name          (:spawn/name spawn)
     :stage/family        (or (:spawn/family spawn) :ingest)
     :stage/connector-ref (or (:spawn/connector-ref spawn)
                               (:stage/connector-ref parent-stage-def))
     :stage/config        (:spawn/config spawn)
     :stage/dependencies  [parent-id]
     :stage/input-datasets  []
     :stage/output-datasets (or (:spawn/output-datasets spawn) [])}))

(defn- stage->task-def
  "Convert a pipeline stage to a DAG task definition."
  [{:stage/keys [id dependencies]}]
  {:task/id id :task/deps (set dependencies)})

(defn- depends-on?
  "True when a stage-def's dependencies include the given parent-id."
  [parent-id [_sid sd]]
  (when (some #{parent-id} (:stage/dependencies sd)) _sid))

(defn- downstream-stage-ids
  "Return the set of stage-map keys whose :stage/dependencies include parent-id."
  [stage-map parent-id]
  (into #{} (keep #(depends-on? parent-id %)) stage-map))

(defn- inject-spawned-stages!
  "Add spawned stage tasks to run-atom and return updated stage-map.
   Each spawned task depends on the parent stage; it becomes ready once the
   parent completes. Inherits connector-ref from parent when omitted.

   Also adds each spawned stage as a dependency of any stages that already
   depend on the parent, so those downstream stages wait for and collect
   records from all spawned children."
  [run-atom stage-map spawns parent-stage-def]
  (let [parent-id (:stage/id parent-stage-def)
        ds-ids    (downstream-stage-ids stage-map parent-id)]
    (letfn [(add-spawned-dep [sm' ds-id stage-id]
              (update-in sm' [ds-id :stage/dependencies] conj stage-id))
            (register-spawn [sm spawn]
              (let [stage-def (spawn->stage-def spawn parent-stage-def)
                    stage-id  (:stage/id stage-def)]
                ;; Register the spawned task — depends on parent only.
                (swap! run-atom update :run/tasks assoc stage-id
                       (dag/create-task-state stage-id #{parent-id}))
                ;; Wire spawned stage into each downstream stage's dep-set so
                ;; those stages wait for (and collect records from) all children.
                (doseq [ds-id ds-ids]
                  (swap! run-atom update-in [:run/tasks ds-id :task/deps] conj stage-id))
                ;; Mirror the dep-set update in the stage-map so collect-inputs
                ;; can look up the spawned stage's records via its UUID.
                (reduce #(add-spawned-dep %1 %2 stage-id)
                        (assoc sm stage-id stage-def)
                        ds-ids)))]
      (reduce register-spawn stage-map spawns))))

;;------------------------------------------------------------------------------ Scheduling loop

(defn- step-pipeline
  "Execute the next ready stage. Returns [stage-outputs stage-runs cursors stage-map].
   If the stage defines a :stage/spawn-fn, spawned child tasks are injected into
   the run-atom and stage-map before the parent is marked complete."
  [run-atom stage-map connectors context stage-outputs stage-runs cursors]
  (let [stage-id  (first (dag/ready-tasks @run-atom))
        stage-def (get stage-map stage-id)
        _         (dag/transition-task! run-atom stage-id :ready nil)
        _         (dag/transition-task! run-atom stage-id :running nil)
        {:keys [datasets records]} (collect-inputs stage-def stage-map stage-outputs)
        result    (run-stage stage-def connectors context datasets records)

        ;; Inject spawned child stages before marking parent terminal so they
        ;; depend on parent-id and become ready once parent completes.
        spawns         (when (= :completed (:status result))
                         (generate-spawns stage-def context (:records result)))
        new-stage-map  (if (seq spawns)
                         (inject-spawned-stages! run-atom stage-map spawns stage-def)
                         stage-map)
        _              (apply-stage-result! run-atom stage-id result)]
    [(if (:records result) (assoc stage-outputs stage-id (:records result)) stage-outputs)
     (conj stage-runs (dissoc result :records :cursor))
     (if (:cursor result)
       (assoc cursors stage-id (cursor-entry stage-def (:schema-name result) (:cursor result)))
       cursors)
     new-stage-map]))

(defn- run-pipeline-dag
  "Loop over the DAG until all stages reach a terminal state.
   Returns {:stage-runs [...] :cursors {...} :failed? bool}."
  [run-atom stage-map connectors context]
  (loop [stage-outputs {}
         stage-runs    []
         cursors       {}
         sm            stage-map]
    (if (dag/all-terminal? @run-atom)
      {:stage-runs stage-runs
       :cursors    cursors
       :failed?    (seq (:run/failed @run-atom))}
      (let [[outputs' runs' cursors' sm']
            (step-pipeline run-atom sm connectors context stage-outputs stage-runs cursors)]
        (recur outputs' runs' cursors' sm')))))

;;------------------------------------------------------------------------------ Public API

(defn execute-pipeline
  "Execute a pipeline using dag-executor for dependency scheduling.
   Runs stages in topological order. Each stage result feeds into dependent stages.
   Stops on first failure — skips all remaining pending stages.

   context may include:
     :transforms  — map of keyword → (fn [records config] -> records)
     :spawn-fns   — map of keyword → (fn [records config] -> [spawn-request ...])"
  [pipeline-def connectors context]
  (let [validation (pipeline/validate-pipeline pipeline-def)]
    (if-not (:success? validation)
      (schema/failure-with-errors :pipeline-run (:errors validation))
      (let [{:pipeline/keys [id version stages mode]} pipeline-def
            run-result   (core/create-pipeline-run
                          {:pipeline-run/pipeline-id id
                           :pipeline-run/version version
                           :pipeline-run/mode (or mode :full-refresh)})
            pipeline-run (:pipeline-run run-result)
            stage-map    (into {} (map (juxt :stage/id identity)) stages)
            task-defs    (map stage->task-def stages)
            run-atom     (dag/create-run-atom
                           (dag/create-dag-from-tasks (random-uuid) task-defs))
            {:keys [stage-runs cursors failed?]}
            (run-pipeline-dag run-atom stage-map connectors context)
            final-run    (assoc pipeline-run
                                :pipeline-run/status (if failed? :failed :completed)
                                :pipeline-run/stage-runs stage-runs
                                :pipeline-run/connector-cursors cursors
                                :pipeline-run/started-at (:pipeline-run/created-at pipeline-run)
                                :pipeline-run/completed-at (Instant/now))]
        (if failed?
          (schema/failure :pipeline-run
                          (msg/t :run/stage-failed
                                 {:name (:stage/name (first (filter #(= :failed (:status %)) stage-runs)))})
                          {:pipeline-run final-run})
          (schema/success :pipeline-run final-run))))))
