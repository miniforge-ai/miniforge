;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-preparation
  "Construct pipelines and execution contexts without an upward runner dependency."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.dag-executor.interface :as dag-exec]
            [ai.miniforge.llm.interface :as llm-impl]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.tenancy.interface :as tenancy]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-environment :as env]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} legacy-phase-interceptor
  [phase-def]
  (phase/get-phase-interceptor
   {:phase (:phase/id phase-def)
    :config phase-def}))

(defn ^{:stratum 0} validate-pipeline
  "Validate a workflow pipeline. Returns {:valid? bool :errors [] :warnings []}."
  [workflow]
  (let [phase-validation (phase/validate-pipeline workflow)
        machine-validation (workflow-fsm/validate-execution-machine workflow)]
    {:valid? (and (:valid? phase-validation)
                  (:valid? machine-validation))
     :errors (vec (concat (:errors phase-validation)
                          (:errors machine-validation)))
     :warnings (vec (concat (:warnings phase-validation)
                            (:warnings machine-validation)))}))

(defn ^{:stratum 0} acquire-environment
  "Acquire an isolated execution environment (worktree or Docker capsule).
   Returns [acquired-env opts'] where acquired-env is nil when pre-acquired."
  [workflow input opts]
  (if (and (:executor opts) (:environment-id opts))
    [nil opts]
    (let [repo-url (get opts :repo-url (get input :repo-url))
          branch   (get opts :branch (get input :branch "main"))
          repo-path (get opts :repo-path (get input :repo-path "."))
          acquired (env/acquire-execution-environment!
                    (:workflow/id workflow)
                    {:repo-url        repo-url
                     :branch          branch
                     :repo-path       repo-path
                     :execution-mode  (:execution-mode opts)
                     :executor-config (:executor-config opts)
                     :runtime-config  (:runtime-config opts)
                     :resume-workspace (:resume-workspace opts)})]
      [acquired (if acquired
                  (merge opts {:branch branch} acquired)
                  opts)])))

(defn ^{:stratum 0} admit-input
  "Caller input as a fresh run admits it, once: without the input keys a
   pipeline phase's lifecycle owns (its interceptor's :owned-input-keys).
   Only that lifecycle writes them, so a caller's value is never trusted. A
   resume is not admitted: it continues the checkpointed run's input whole,
   lifecycle-owned keys included."
  [pipeline input opts]
  (if (or (not (map? input)) (:resume-machine-snapshot opts) (:resume-phase-results opts))
    input
    (apply dissoc input (mapcat :owned-input-keys pipeline))))

(defn- ^{:stratum 0} governed-capsule? [opts]
  (boolean (and (= :governed (:execution-mode opts))
                (:executor opts) (:environment-id opts))))

(defn- ^{:stratum 0} unowned-run-anomaly
  "With `:require-operator?` set, return an anomaly when the run would carry
   no valid acting context: the resume snapshot's on a resume, else the
   caller's `:acting`. Otherwise nil."
  [opts]
  (when (:require-operator? opts)
    (let [snapshot (:resume-machine-snapshot opts)
          acting (if (and snapshot (:resume-phase-results opts))
                   (tenancy/require-acting snapshot :execution/acting)
                   (tenancy/require-acting opts :acting))]
      (when (anomaly/anomaly? acting)
        (anomaly/anomaly :invalid-input
                         (messages/t :tenancy/operator-required)
                         {:tenancy/refusal acting})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} governed-capsule-missing-anomaly
  "Return an `:invalid-input` anomaly when `:governed` mode is requested
   without a pre-acquired capsule executor + environment-id, otherwise nil.

   Governed mode forbids worktree fallback (N11 §7.4); the runner refuses
   to silently downgrade. Check before assembling the execution context."
  [opts]
  (let [mode (get opts :execution-mode :local)]
    (when (and (= :governed mode) (not (governed-capsule? opts)))
      (anomaly/anomaly :invalid-input
                       (messages/t :governed/no-capsule)
                       {:execution-mode :governed
                        :has-executor? (some? (get opts :executor))
                        :has-environment-id? (some? (get opts :environment-id))}))))

(defn- ^{:stratum 1} assemble-initial-context
  "Construct the initial execution context map. Caller is responsible for
   running [[governed-capsule-missing-anomaly]] first; reaching this function
   with `:governed` mode but no capsule is a programmer error."
  [workflow input opts run-pipeline]
  (let [resume-machine-snapshot (:resume-machine-snapshot opts)
        resume-phase-results (:resume-phase-results opts)
        mode      (get opts :execution-mode :local)
        governed? (governed-capsule? opts)
        capsule-exec-fn (when governed?
                          (llm-impl/capsule-exec-fn
                           dag-exec/execute!
                           (get opts :executor)
                           (get opts :environment-id)
                           ;; The capsule's own checkout: the host worktree path
                           ;; a runner-acquired capsule records does not exist in it.
                           (or (get opts :capsule-workdir)
                               (get opts :worktree-path)
                               (defaults/default-workdir))))
        base-context (if (and resume-machine-snapshot resume-phase-results)
                       (ctx/restore-context workflow input
                                            resume-machine-snapshot
                                            resume-phase-results
                                            opts)
                       (ctx/create-context workflow input opts))
        environment-metadata (or (get opts :environment-metadata)
                                 (:execution/environment-metadata base-context))]
    (-> base-context
        (assoc :execution/executor (get opts :executor)
               :execution/environment-id (get opts :environment-id)
               :execution/worktree-path (get opts :worktree-path
                                             (get opts :sandbox-workdir))
               :execution/mode mode
               :execution/capsule-workdir (get opts :capsule-workdir)
               :execution/started-at (java.time.Instant/now)
               :execution/environment-metadata environment-metadata
               :execution/execute-fn (when governed? dag-exec/execute!)
               :execution/exec-fn capsule-exec-fn
               :execution/task-branch (when governed?
                                        (str (defaults/task-branch-prefix)
                                             (:environment-id opts)))
               :execution/run-pipeline-fn run-pipeline))))

(defn ^{:stratum 1} build-pipeline
  "Build interceptor pipeline from workflow config."
  [workflow]
  (let [pipeline-config (:workflow/pipeline workflow)]
    (if (seq pipeline-config)
      (mapv phase/get-phase-interceptor pipeline-config)
      (mapv legacy-phase-interceptor
            (:workflow/phases workflow)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-initial-context
  "Build the initial execution context from workflow, input, and opts.

   Returns the assembled execution context on success, or an
   `:invalid-input` anomaly when `:governed` mode is requested but no
   capsule executor + `:environment-id` were supplied in `opts` (governed
   mode forbids worktree fallback, N11 §7.4), or when `:require-operator?`
   is set and the run has no valid acting context.

   The supplied run-pipeline port is stored unchanged for nested execution;
   context preparation has no dependency on the runner namespace."
  [workflow input opts run-pipeline]
  (or (governed-capsule-missing-anomaly opts)
      (unowned-run-anomaly opts)
      (assemble-initial-context workflow input opts run-pipeline)))

(comment ::runner-preparation)
