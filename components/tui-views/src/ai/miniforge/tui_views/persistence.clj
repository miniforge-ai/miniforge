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
   [ai.miniforge.pr-sync.interface :as pr-sync]))

;------------------------------------------------------------------------------ Layer 0
;; EDN line reading

(defn- safe-read-edn
  "Read a single EDN value from a string, returning nil on parse errors.
   Uses default EDN readers which already handle #uuid and #inst."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _ nil)))

(defn- read-first-and-last
  "Read the first and last EDN events from a workflow file.
   Returns [first-event last-event] or nil on error."
  [file]
  (try
    (with-open [rdr (io/reader file)]
      (let [lines (vec (line-seq rdr))]
        (when (seq lines)
          [(safe-read-edn (first lines))
           (safe-read-edn (peek lines))])))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Workflow reconstruction

(defn- derive-status
  "Derive workflow status from the last event in the file."
  [last-event]
  (case (:event/type last-event)
    :workflow/completed (or (:workflow/status last-event) :success)
    :workflow/failed    :failed
    ;; Still in progress (no terminal event yet) — default clause
    :running))

(defn- derive-phase
  "Derive current/last phase from events."
  [last-event]
  (when-let [phase (:workflow/phase last-event)]
    phase))

(defn- derive-progress
  "Estimate progress from the last event."
  [last-event]
  (case (:event/type last-event)
    :workflow/completed 100
    :workflow/failed    100
    ;; Rough estimate based on event type
    :workflow/phase-completed 60
    :workflow/phase-started   40
    :agent/chunk              50
    :agent/status             50
    :workflow/started         10
    0))

(defn- event-file->workflow
  "Convert a single event file into a workflow summary map for the model.
   Returns nil if the file cannot be read or parsed."
  [file]
  (try
    (when-let [[first-event last-event] (read-first-and-last file)]
      (when (and first-event (:workflow/id first-event))
        (let [workflow-id (:workflow/id first-event)
              name        (or (get-in first-event [:workflow/spec :name])
                             (str "workflow-" (subs (str workflow-id) 0 8)))
              status      (derive-status last-event)
              phase       (or (derive-phase last-event)
                             (derive-phase first-event))
              progress    (derive-progress last-event)
              started-at  (:event/timestamp first-event)]
          (model/make-workflow
           {:id         workflow-id
            :name       name
            :status     status
            :phase      phase
            :progress   progress
            :started-at started-at}))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn events-dir
  "Get the events directory path. Returns a java.io.File."
  []
  (io/file (System/getProperty "user.home") ".miniforge" "events"))

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
  "Fetch open PRs for all configured fleet repositories.
   Returns vector of TrainPR maps, or empty vec on error."
  [& [{:keys [config-path]}]]
  (try
    (pr-sync/fetch-all-fleet-prs (when config-path {:config-path config-path}))
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
