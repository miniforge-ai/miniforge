;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state
  "State management for web dashboard with integration to PR trains and DAGs.")

;------------------------------------------------------------------------------ Layer 0
;; State atom creation

(defn create-state
  "Create dashboard state atom."
  [opts]
  (atom (merge {:event-stream nil
                :pr-train-manager nil
                :repo-dag-manager nil
                :start-time (System/currentTimeMillis)}
               opts)))

;------------------------------------------------------------------------------ Layer 1
;; State accessors

(defn get-uptime
  "Get server uptime in milliseconds."
  [state]
  (- (System/currentTimeMillis) (:start-time @state)))

(defn- safe-call
  "Safely call a function from a namespace, returning default on error."
  [ns-sym fn-sym & args]
  (try
    (when-let [ns (find-ns ns-sym)]
      (when-let [f (ns-resolve ns fn-sym)]
        (apply f args)))
    (catch Exception e
      (println "Error calling" fn-sym ":" (.getMessage e))
      nil)))

;------------------------------------------------------------------------------ Layer 2
;; PR Train state

(defn get-trains
  "Get all PR trains."
  [state]
  (if-let [mgr (:pr-train-manager @state)]
    (or (safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])
    []))

(defn get-train-detail
  "Get detailed view of a PR train."
  [state train-id]
  (if-let [mgr (:pr-train-manager @state)]
    (or (safe-call 'ai.miniforge.pr-train.interface 'get-train mgr (parse-uuid train-id))
        {:error "Train not found"})
    {:error "PR train manager not available"}))

(defn train-action!
  "Execute action on a PR train."
  [state train-id action]
  (when-let [mgr (:pr-train-manager @state)]
    (let [tid (parse-uuid train-id)]
      (case action
        "pause" (safe-call 'ai.miniforge.pr-train.interface 'pause-train mgr tid "Manual pause")
        "resume" (safe-call 'ai.miniforge.pr-train.interface 'resume-train mgr tid)
        "merge-next" (safe-call 'ai.miniforge.pr-train.interface 'merge-next mgr tid)
        nil))))

;------------------------------------------------------------------------------ Layer 3
;; DAG state

(defn get-dags
  "Get all repository DAGs."
  [state]
  (if-let [mgr (:repo-dag-manager @state)]
    (or (safe-call 'ai.miniforge.repo-dag.interface 'get-all-dags mgr) [])
    []))

(defn get-dag-state
  "Get DAG kanban state for visualization."
  [state]
  (let [dags (get-dags state)
        trains (get-trains state)]
    {:dags dags
     :trains trains
     :repos (mapcat :dag/repos dags)
     :tasks (mapcat (fn [train]
                      (map (fn [pr]
                             {:id (:pr/number pr)
                              :repo (:pr/repo pr)
                              :title (:pr/title pr)
                              :status (case (:pr/status pr)
                                        (:draft :open) :ready
                                        :reviewing :running
                                        :merged :done
                                        :failed :blocked
                                        :blocked)
                              :train-id (:train/id train)
                              :dependencies (:pr/depends-on pr)})
                           (:train/prs train)))
                    trains)}))

;------------------------------------------------------------------------------ Layer 4
;; Fleet state aggregation

(defn- calculate-risk-score
  "Calculate AI-powered risk score for a train or PR."
  [entity]
  (let [base-score 0
        ;; Factor 1: CI status
        ci-penalty (case (:pr/ci-status entity (:ci-status entity))
                     :failed 30
                     :running 5
                     :pending 10
                     0)
        ;; Factor 2: Number of dependencies
        dep-penalty (* 3 (count (:pr/depends-on entity [])))
        ;; Factor 3: Status
        status-penalty (case (:pr/status entity (:train/status entity))
                        :changes-requested 15
                        :reviewing 5
                        :merging 10
                        0)
        ;; Factor 4: Blocking PRs
        blocking-penalty (* 5 (count (:train/blocking-prs entity [])))]
    (min 100 (+ base-score ci-penalty dep-penalty status-penalty blocking-penalty))))

(defn get-fleet-state
  "Get aggregated fleet state across all repos and trains."
  [state]
  (let [trains (get-trains state)
        dags (get-dags state)
        total-prs (reduce + 0 (map #(count (:train/prs %)) trains))
        active-trains (filter #(#{:open :reviewing :merging} (:train/status %)) trains)
        repos (set (mapcat #(map :pr/repo (:train/prs %)) trains))]
    {:summary {:total-trains (count trains)
               :active-trains (count active-trains)
               :total-prs total-prs
               :repos (count repos)
               :dags (count dags)}
     :trains trains
     :repos (group-by identity (mapcat #(map :pr/repo (:train/prs %)) trains))
     :health {:healthy (count (filter #(< (calculate-risk-score %) 20) trains))
              :warning (count (filter #(and (>= (calculate-risk-score %) 20)
                                           (< (calculate-risk-score %) 50)) trains))
              :critical (count (filter #(>= (calculate-risk-score %) 50) trains))}}))

;------------------------------------------------------------------------------ Layer 5
;; Workflow state

(defn get-workflows
  "Get workflows from event stream."
  [state]
  (try
    (if-let [stream (:event-stream @state)]
      (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
        (if es-ns
          (let [get-events (ns-resolve es-ns 'get-events)
                events (get-events stream)]
            (->> events
                 (filter #(#{:workflow/started :workflow/completed} (:event/type %)))
                 (group-by #(or (:workflow-id %) (:workflow/id %)))
                 (map (fn [[id wf-events]]
                        (let [started (first (filter #(= :workflow/started (:event/type %)) wf-events))
                              completed (first (filter #(= :workflow/completed (:event/type %)) wf-events))]
                          {:id id
                           :name (get-in started [:spec :name] (str "Workflow " (subs (str id) 0 8)))
                           :status (if completed :completed :running)
                           :phase (get-in started [:phase] "unknown")
                           :progress (if completed 100 50)
                           :started-at (:timestamp started)
                           :completed-at (:timestamp completed)})))
                 (take 50)
                 vec))
          []))
      [])
    (catch Exception e
      (println "Error getting workflows:" (.getMessage e))
      [])))

(defn get-workflow-detail
  "Get workflow detail."
  [state id]
  (let [workflows (get-workflows state)]
    (or (first (filter #(= (str (:id %)) id) workflows))
        {:error "Workflow not found"})))

;------------------------------------------------------------------------------ Layer 6
;; Dashboard state

(defn get-stats
  "Get high-level dashboard statistics."
  [state]
  (let [fleet (get-fleet-state state)
        workflows (get-workflows state)]
    {:trains {:total (get-in fleet [:summary :total-trains])
              :active (get-in fleet [:summary :active-trains])}
     :prs {:total (get-in fleet [:summary :total-prs])
           :ready (reduce + 0 (map #(count (:train/ready-to-merge %)) (:trains fleet)))
           :blocked (reduce + 0 (map #(count (:train/blocking-prs %)) (:trains fleet)))}
     :health {:healthy (get-in fleet [:health :healthy])
              :warning (get-in fleet [:health :warning])
              :critical (get-in fleet [:health :critical])}
     :workflows {:total (count workflows)
                 :running (count (filter #(= :running (:status %)) workflows))
                 :completed (count (filter #(= :completed (:status %)) workflows))}}))

(defn get-risk-analysis
  "Get AI-powered risk analysis for fleet."
  [state]
  (let [trains (get-trains state)
        risks (map (fn [train]
                    (let [score (calculate-risk-score train)]
                      {:train-id (:train/id train)
                       :train-name (:train/name train)
                       :risk-score score
                       :risk-level (cond
                                     (< score 20) :low
                                     (< score 50) :medium
                                     :else :high)
                       :factors (cond-> []
                                  (seq (:train/blocking-prs train))
                                  (conj {:type :blocking-prs
                                         :count (count (:train/blocking-prs train))
                                         :severity :high})

                                  (some #(= :failed (:pr/ci-status %)) (:train/prs train))
                                  (conj {:type :ci-failures
                                         :count (count (filter #(= :failed (:pr/ci-status %)) (:train/prs train)))
                                         :severity :high})

                                  (> (count (:train/prs train)) 5)
                                  (conj {:type :large-train
                                         :count (count (:train/prs train))
                                         :severity :medium}))}))
                  trains)]
    {:risks (sort-by :risk-score > risks)
     :summary {:high (count (filter #(= :high (:risk-level %)) risks))
               :medium (count (filter #(= :medium (:risk-level %)) risks))
               :low (count (filter #(= :low (:risk-level %)) risks))}}))

;------------------------------------------------------------------------------ Layer 7
;; Activity tracking

(defn get-recent-activity
  "Get recent activity across fleet.
"
  [state]
  (try
    (let [trains (get-trains state)
          train-events (mapcat (fn [train]
                                 (map (fn [pr]
                                        {:type :pr-status
                                         :timestamp (:train/updated-at train)
                                         :train-id (:train/id train)
                                         :train-name (:train/name train)
                                         :pr-number (:pr/number pr)
                                         :status (:pr/status pr)
                                         :message (str "PR #" (:pr/number pr) " " (name (:pr/status pr)))})
                                      (:train/prs train)))
                               trains)]
      (->> train-events
           (sort-by :timestamp #(compare %2 %1))
           (take 20)
           vec))
    (catch Exception e
      (println "Error getting recent activity:" (.getMessage e))
      [])))

(defn get-evidence-state
  "Get evidence artifacts state."
  [state]
  (let [trains (get-trains state)]
    {:trains (map (fn [train]
                   {:train-id (:train/id train)
                    :train-name (:train/name train)
                    :evidence-bundle-id (:train/evidence-bundle-id train)
                    :pr-count (count (:train/prs train))
                    :has-evidence (some? (:train/evidence-bundle-id train))})
                 trains)}))

;------------------------------------------------------------------------------ Layer 8
;; Composite state

(defn get-dashboard-state
  "Get complete dashboard state for initial load."
  [state]
  {:stats (get-stats state)
   :fleet (get-fleet-state state)
   :risk (get-risk-analysis state)
   :activity (get-recent-activity state)
   :workflows (take 10 (get-workflows state))})
