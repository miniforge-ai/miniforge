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

(ns ai.miniforge.tui-views.persistence
  "Load persisted workflow events from disk on TUI startup.

   Scans ~/.miniforge/events/ for workflow event files, reads the first
   and last events from each file, and reconstructs workflow summaries
   for the TUI model. This gives the TUI immediate visibility into past
   workflows without requiring a running event stream.

   Layer 1: Pure data loading, no side effects beyond file I/O."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.pr-sync.interface :as pr-sync]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0
;; EDN line reading

(def lifecycle-event-types
  #{:workflow/started :workflow/completed :workflow/failed})

(defn safe-read-edn
  "Read a single EDN value from a string, returning nil on parse errors.
   Uses default EDN readers which already handle #uuid and #inst."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn read-events
  "Read all valid EDN events from a workflow file in file order."
  [file]
  (try
    (with-open [rdr (io/reader file)]
      (->> (line-seq rdr)
           (keep safe-read-edn)
           vec))
    (catch Exception _
      [])))

;------------------------------------------------------------------------------ Layer 1
;; Workflow reconstruction helpers

(defn workflow-start-event
  [events]
  (first (filter #(= :workflow/started (:event/type %)) events)))

(defn workflow-terminal-event
  [events]
  (last (filter #(contains? lifecycle-event-types (:event/type %)) events)))

(defn workflow-id-from-events
  [events]
  (some :workflow/id events))

(defn top-level-workflow-events?
  "True when the file contains lifecycle events for a real workflow.
   Phase-only files are typically DAG child traces and should not appear in the
   top-level workflow list."
  [events]
  (boolean (some #(contains? lifecycle-event-types (:event/type %)) events)))

(defn event-phase
  [event]
  (or (:workflow/phase event) (:phase event)))

(defn phase-status
  [outcome]
  (case outcome
    :failure :failed
    :failed  :failed
    :skipped :skipped
    :success :success
    :running))

(defn update-phase-entry
  [phases phase status duration-ms]
  (let [entry (cond-> {:phase phase :status status}
                duration-ms (assoc :duration-ms duration-ms))
        idx (first (keep-indexed (fn [i p] (when (= phase (:phase p)) i)) phases))]
    (if idx
      (assoc phases idx (merge (get phases idx) entry))
      (conj (vec phases) entry))))

(defn infer-artifact-type
  "Infer artifact type from map keys when :type is not set."
  [artifact]
  (cond
    (:type artifact)         (:type artifact)
    (:code/files artifact)   :code
    (:plan/tasks artifact)   :plan
    (:test/files artifact)   :test
    (:review/id artifact)    :review
    (:evidence/id artifact)  :evidence
    :else                    :unknown))

(defn infer-artifact-name
  "Derive a display name from artifact data."
  [artifact type]
  (or (:name artifact)
      (:code/summary artifact)
      (:plan/name artifact)
      (str (name type) " artifact")))

(defn normalize-artifact
  [artifact phase]
  (if (map? artifact)
    (let [type (infer-artifact-type artifact)]
      (assoc artifact
             :phase phase
             :type type
             :name (infer-artifact-name artifact type)))
    {:id artifact :phase phase :type :unknown :name (str artifact)}))

(defn nested-dag-artifacts
  "Extract artifacts persisted inside DAG terminal errors/results."
  [event]
  (or (get-in event [:workflow/error-details :dag-result :artifacts])
      (mapcat #(get-in % [:dag-result :artifacts] [])
              (get-in event [:workflow/error-details :errors] []))
      []))

(defn empty-detail
  [workflow-id]
  {:workflow-id workflow-id
   :phases []
   :current-phase nil
   :current-agent nil
   :agent-output ""
   :agents {}
   :evidence nil
   :artifacts []
   :expanded-nodes #{}
   :focused-pane 0
   :selected-pr nil
   :pr-readiness nil
   :pr-risk nil
   :selected-train nil
   :duration-ms nil
   :error nil})

(defn ensure-evidence-intent
  [detail event]
  (if-let [spec (:workflow/spec event)]
    (assoc-in detail [:evidence :intent]
              {:description (or (:name spec)
                                (:workflow/name spec))
               :spec spec})
    detail))

(defn append-artifacts
  [detail phase artifacts]
  (if (seq artifacts)
    (update detail :artifacts into (map #(normalize-artifact % phase) artifacts))
    detail))

(defn append-validation-result
  [detail event passed?]
  (update-in detail [:evidence :validation :results]
             (fnil conj [])
             {:gate (:gate/id event)
              :passed? passed?
              :event/type (:event/type event)
              :event/timestamp (:event/timestamp event)}))

(defn apply-detail-event
  [detail event]
  (case (:event/type event)
    :workflow/started
    (ensure-evidence-intent detail event)

    :workflow/phase-started
    (let [phase (event-phase event)]
      (-> detail
          (assoc :current-phase phase)
          (update :phases update-phase-entry phase :running nil)))

    :workflow/phase-completed
    (let [phase (event-phase event)
          outcome (:phase/outcome event)
          duration-ms (:phase/duration-ms event)
          artifacts (:phase/artifacts event)]
      (-> detail
          (assoc :current-phase phase)
          (update :phases update-phase-entry phase (phase-status outcome) duration-ms)
          (append-artifacts phase artifacts)))

    :agent/started
    (let [agent (:agent/id event)
          entry {:status :started :message (:message event)}]
      (-> detail
          (assoc :current-agent (assoc entry :agent agent))
          (assoc-in [:agents agent] entry)))

    :agent/completed
    (let [agent (:agent/id event)
          entry {:status :completed :message (:message event)}]
      (-> detail
          (assoc :current-agent (assoc entry :agent agent))
          (assoc-in [:agents agent] entry)))

    :agent/failed
    (let [agent (:agent/id event)
          entry {:status :failed :message (:message event)}]
      (-> detail
          (assoc :current-agent (assoc entry :agent agent))
          (assoc :error (or (:agent/error event) (:message event)))
          (assoc-in [:agents agent] entry)))

    :agent/status
    (let [agent (:agent/id event)
          entry {:status (:status/type event) :message (:message event)}]
      (-> detail
          (assoc :current-agent (assoc entry :agent agent))
          (assoc-in [:agents agent] entry)))

    :agent/chunk
    (update detail :agent-output str (or (:chunk/delta event) ""))

    :gate/started
    detail

    :gate/passed
    (append-validation-result detail event true)

    :gate/failed
    (append-validation-result detail event false)

    :workflow/completed
    (-> detail
        (assoc :duration-ms (:workflow/duration-ms event))
        (append-artifacts (or (:current-phase detail) :done) (nested-dag-artifacts event)))

    :workflow/failed
    (-> detail
        (assoc :error (or (:workflow/failure-reason event)
                          (get-in event [:workflow/error-details :message])))
        (append-artifacts (or (:current-phase detail) :failed) (nested-dag-artifacts event)))

    detail))

(defn detail-from-events
  [workflow-id events]
  (reduce apply-detail-event (empty-detail workflow-id) events))

(defn workflow-name
  [workflow-id events]
  (or (get-in (workflow-start-event events) [:workflow/spec :name])
      (get-in (workflow-start-event events) [:workflow/spec :workflow/name])
      (str "workflow-" (subs (str workflow-id) 0 8))))

(def ^:private stale-threshold-ms
  "Workflows without a terminal event and no activity for this long are :stale."
  (* 60 60 1000)) ;; 1 hour

(defn derive-status
  "Derive workflow status from the event sequence.
   Accepts an optional file for age-based stale detection."
  ([events] (derive-status events nil))
  ([events file]
   (let [terminal (workflow-terminal-event events)
         last-event (last events)]
     (case (:event/type terminal)
       :workflow/completed (or (:workflow/status terminal) :success)
       :workflow/failed    :failed
       (let [active? (contains? #{:workflow/phase-completed :workflow/phase-started
                                   :agent/chunk :agent/status :workflow/started}
                                (:event/type last-event))
             stale?  (when (and active? file (.exists ^java.io.File file))
                       (> (- (System/currentTimeMillis) (.lastModified ^java.io.File file))
                          stale-threshold-ms))]
         (if stale? :stale :running))))))

(defn derive-phase
  "Derive current/last phase from events."
  [events]
  (some->> events reverse (keep event-phase) first))

(defn derive-progress
  "Estimate progress from the full event sequence."
  [events]
  (let [status (derive-status events)
        phase-completions (count (filter #(= :workflow/phase-completed (:event/type %)) events))
        last-event (last events)]
    (cond
      (#{:success :failed :cancelled :completed} status) 100
      (pos? phase-completions) (min 95 (* 20 phase-completions))
      (= :workflow/phase-started (:event/type last-event)) 40
      (#{:agent/chunk :agent/status} (:event/type last-event)) 50
      (= :workflow/started (:event/type last-event)) 10
      :else 0)))

(defn event-file->workflow
  "Convert a single event file into a workflow summary map for the model.
   Returns nil if the file cannot be read or parsed."
  [file]
  (try
    (let [events (read-events file)
          workflow-id (workflow-id-from-events events)]
      (when (and workflow-id
                 (seq events)
                 (top-level-workflow-events? events))
        (let [detail     (detail-from-events workflow-id events)
              start      (workflow-start-event events)
              started-at (or (:event/timestamp start)
                             (:event/timestamp (first events)))
              workflow   (model/make-workflow
                          {:id         workflow-id
                           :name       (workflow-name workflow-id events)
                           :status     (derive-status events file)
                           :phase      (derive-phase events)
                           :progress   (derive-progress events)
                           :started-at started-at})]
          (assoc workflow
                 :agents (:agents detail)
                 :gate-results (get-in detail [:evidence :validation :results] [])
                 :duration-ms (:duration-ms detail)
                 :error (:error detail)
                 :detail-snapshot detail))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn events-dir
  "Get the events directory path. Returns a java.io.File."
  []
  (io/file (System/getProperty "user.home") ".miniforge" "events"))

(defn workflow-events-file
  "Resolve the persisted event file for a workflow UUID."
  [workflow-id & [{:keys [dir]}]]
  (io/file (or dir (events-dir)) (str workflow-id ".edn")))

(defn load-workflows
  "Scan the events directory and load workflow summaries.

   Options:
   - :limit    - Maximum number of workflows to load (default 100, most recent first)
   - :dir      - Override events directory (for testing)

   Returns: Vector of workflow summary maps sorted by started-at (newest first)."
  [& [{:keys [limit dir] :or {limit 100}}]]
  (let [events-directory (or dir (events-dir))]
    (if (and (.exists events-directory) (.isDirectory events-directory))
      (let [edn-files (->> (.listFiles events-directory)
                          (filter #(.endsWith (.getName %) ".edn"))
                          ;; Sort by modification time, newest first
                          (sort-by #(- (.lastModified %)))
                          ;; Take only the most recent N files
                          (take limit))]
        (->> edn-files
             (pmap event-file->workflow)
             (filter some?)
             (sort-by :started-at #(compare %2 %1))
             vec))
      [])))

(defn load-workflow-detail
  "Load the reconstructed detail snapshot for a single workflow from disk."
  [workflow-id & [{:keys [dir]}]]
  (let [file (workflow-events-file workflow-id {:dir dir})]
    (when (.exists file)
      (let [events (read-events file)]
        (when (seq events)
          (detail-from-events workflow-id events))))))

(defn load-workflows-into-model
  "Load persisted workflows and merge them into the given model.

   Arguments:
   - model - The initial TUI model from model/init-model
   - opts  - Options passed to load-workflows

   Returns: Updated model with :workflows populated and :last-updated set."
  [model & [opts]]
  (let [workflows (load-workflows opts)]
    (if (seq workflows)
      (-> model
          (assoc :workflows workflows)
          (assoc :last-updated (java.util.Date.))
          (assoc :flash-message (str "Loaded " (count workflows) " workflows from disk")))
      model)))

;------------------------------------------------------------------------------ Layer 3
;; PR enrichment

(defn packs-dir
  "Get the policy packs directory path."
  []
  (io/file (System/getProperty "user.home") ".miniforge" "packs"))

(defn load-policy-packs
  "Load policy packs from ~/.miniforge/packs/.
   Returns vector of PackManifest maps, or empty vec on error."
  []
  (try
    (let [dir (packs-dir)]
      (if (and (.exists dir) (.isDirectory dir))
        (let [result (policy-pack/load-all-packs (.getPath dir))]
          (get result :loaded []))
        []))
    (catch Exception _ [])))

(defn enrich-pr-in-context
  "Enrich a single PR with readiness and risk, using its repo-level
   train snapshot as context (so dependency and fanout factors are correct)."
  [train-snapshot pr]
  (try
    (let [readiness (pr-train/explain-readiness train-snapshot pr)
          risk (pr-train/assess-risk train-snapshot pr {} {} {})]
      (assoc pr
             :pr/readiness readiness
             :pr/risk risk))
    (catch Exception _
      ;; If enrichment fails, return PR unchanged (fallback to naive derivation)
      pr)))

(defn enrich-prs
  "Enrich a collection of PRs with readiness and risk from pr-train component.
   Groups PRs by repo and builds per-repo train snapshots so that dependency,
   fanout, and merge-order factors are computed correctly within each repo."
  [prs]
  (let [by-repo (group-by :pr/repo prs)
        enriched (mapcat (fn [[_repo repo-prs]]
                           (let [snapshot {:train/prs (vec repo-prs)}]
                             (mapv (partial enrich-pr-in-context snapshot) repo-prs)))
                         by-repo)
        ;; Preserve original ordering
        enriched-map (into {}
                           (map (fn [pr] [[(:pr/repo pr) (:pr/number pr)] pr]))
                           enriched)]
    (mapv (fn [pr] (get enriched-map [(:pr/repo pr) (:pr/number pr)] pr)) prs)))

;------------------------------------------------------------------------------ Layer 4
;; PR loading

(defn load-fleet-repos
  "Load configured fleet repositories from config.
   Returns vector of normalized repo slugs, or empty vec on error."
  [& [{:keys [config-path]}]]
  (try
    (if config-path
      (pr-sync/get-configured-repos config-path)
      (pr-sync/get-configured-repos))
    (catch Exception _ [])))

(defn load-fleet-repos-into-model
  "Load configured fleet repos and merge into model."
  [model & [opts]]
  (let [repos (load-fleet-repos opts)]
    (assoc model :fleet-repos (vec repos))))

(defn load-pr-items
  "Fetch PRs for all configured fleet repositories.
   Enriches each PR with readiness and risk from pr-train component.
   Returns vector of enriched TrainPR maps, or empty vec on error.

   Options:
   - :config-path - Override config file path
   - :state       - :open (default), :closed, :merged, :all"
  [& [{:keys [config-path state]}]]
  (try
    (let [opts (cond-> {}
                 config-path (assoc :config-path config-path)
                 state       (assoc :state state))
          prs (pr-sync/fetch-all-fleet-prs (when (seq opts) opts))]
      (enrich-prs prs))
    (catch Exception _ [])))

(defn load-pr-items-into-model
  "Load PRs from configured repos and merge into model.

   Arguments:
   - model - The TUI model
   - opts  - Options: :config-path

   Returns: Updated model with :pr-items populated."
  [model & [opts]]
  (let [prs (load-pr-items opts)]
    (if (seq prs)
      (-> model
          (assoc :pr-items (vec prs))
          (assoc :last-updated (java.util.Date.))
          (assoc :flash-message (str "Loaded " (count prs) " PRs from "
                                     (count (distinct (map :pr/repo prs))) " repo(s)")))
      model)))

(defn discover-repos
  "Discover repos from a GitHub org/user and add to fleet config.
   Returns result map from pr-sync/discover-repos!."
  [owner]
  (try
    (pr-sync/discover-repos! {:owner owner})
    (catch Exception e
      {:success? false :error (.getMessage e)})))

(defn browse-repos
  "Browse repositories from providers (read-only).
   Returns result map from pr-sync/list-org-repos."
  [& [{:keys [owner limit provider] :or {limit 100 provider :github}}]]
  (try
    (pr-sync/list-org-repos (cond-> {}
                              owner (assoc :owner owner)
                              provider (assoc :provider provider)
                              (integer? limit) (assoc :limit limit)))
    (catch Exception e
      {:success? false :owner owner :provider provider :error (.getMessage e)})))

(defn load-all-into-model
  "Load both workflows and PRs into model on startup.

   Arguments:
   - model - The initial TUI model
   - opts  - {:limit N :config-path path}

   Returns: Updated model with :workflows and :pr-items populated."
  [model & [opts]]
  (-> model
      (load-workflows-into-model opts)
      (load-fleet-repos-into-model opts)
      (load-pr-items-into-model opts)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load workflows
  (def wfs (load-workflows {:limit 20}))
  (count wfs)
  (first wfs)

  ;; Load into model
  (def m (load-workflows-into-model (model/init-model)))
  (count (:workflows m))
  (:flash-message m)

  ;; Load PRs
  (def m2 (load-pr-items-into-model (model/init-model)))
  (count (:pr-items m2))

  :leave-this-here)
