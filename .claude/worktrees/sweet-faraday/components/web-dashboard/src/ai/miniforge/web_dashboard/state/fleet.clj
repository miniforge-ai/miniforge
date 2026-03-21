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

(ns ai.miniforge.web-dashboard.state.fleet
  "Fleet aggregation, risk scoring, and composite state."
  (:require
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.trains :as trains]
   [ai.miniforge.web-dashboard.state.workflows :as workflows]
   [ai.miniforge.web-dashboard.state.archive :as archive]))

;------------------------------------------------------------------------------ Layer 0
;; Pure scoring and computation

(defn calculate-risk-score
  "Calculate risk score for a train or PR."
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

(defn compute-stats
  "Compute dashboard statistics from trains and workflows data (pure)."
  [trains wfs]
  (let [risk-scores (map calculate-risk-score trains)]
    {:trains {:total (count trains)
              :active (count (filter #(#{:open :reviewing :merging} (:train/status %)) trains))}
     :prs {:total (reduce + 0 (map #(count (:train/prs %)) trains))
           :ready (reduce + 0 (map #(count (:train/ready-to-merge %)) trains))
           :blocked (reduce + 0 (map #(count (:train/blocking-prs %)) trains))}
     :health {:healthy (count (filter #(< % 20) risk-scores))
              :warning (count (filter #(and (>= % 20) (< % 50)) risk-scores))
              :critical (count (filter #(>= % 50) risk-scores))}
     :workflows {:total (count wfs)
                 :running (count (filter #(= :running (:status %)) wfs))
                 :completed (count (filter #(= :completed (:status %)) wfs))}}))

(defn compute-risk-analysis
  "Compute risk analysis from trains data (pure)."
  [trains]
  (let [risks (map (fn [train]
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

;------------------------------------------------------------------------------ Layer 1
;; Fleet state aggregation

(def get-fleet-state
  "Get aggregated fleet state across all repos and trains (cached 10s)."
  (core/ttl-memoize 10000
               (fn [state]
                 (let [trains (trains/get-trains state)
                       dags (trains/get-dags state)
                       configured-repos (trains/get-configured-repos state)
                       total-prs (reduce + 0 (map #(count (:train/prs %)) trains))
                       active-trains (filter #(#{:open :reviewing :merging} (:train/status %)) trains)
                       repos (set (mapcat #(map :pr/repo (:train/prs %)) trains))]
                   {:summary {:total-trains (count trains)
                              :active-trains (count active-trains)
                              :total-prs total-prs
                              :repos (count repos)
                              :configured-repos (count configured-repos)
                              :dags (count dags)}
                    :trains trains
                    :configured-repos configured-repos
                    :repos (group-by identity (mapcat #(map :pr/repo (:train/prs %)) trains))
                    :health {:healthy (count (filter #(< (calculate-risk-score %) 20) trains))
                             :warning (count (filter #(and (>= (calculate-risk-score %) 20)
                                                           (< (calculate-risk-score %) 50)) trains))
                             :critical (count (filter #(>= (calculate-risk-score %) 50) trains))}}))))

;------------------------------------------------------------------------------ Layer 2
;; Composite state

(defn get-risk-analysis
  "Get risk analysis for fleet."
  [state]
  (compute-risk-analysis (trains/get-trains state)))

(defn get-recent-activity
  "Get recent activity across fleet."
  [state]
  (try
    (let [trains (trains/get-trains state)
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
      (println "Error getting recent activity:" (ex-message e))
      [])))

(defn get-evidence-state
  "Get evidence artifacts state from PR trains, live workflows, and archived workflows."
  [state]
  (let [trains (trains/get-trains state)
        train-evidence (map (fn [train]
                              {:source :train
                               :train-id (:train/id train)
                               :train-name (:train/name train)
                               :evidence-bundle-id (:train/evidence-bundle-id train)
                               :pr-count (count (:train/prs train))
                               :has-evidence (some? (:train/evidence-bundle-id train))})
                            trains)
        ;; Live workflows from event stream
        wfs (workflows/get-workflows state)
        live-evidence (keep (fn [wf]
                              (when (#{:completed :failed} (:status wf))
                                {:source :workflow
                                 :workflow-id (:id wf)
                                 :workflow-name (:name wf)
                                 :evidence-bundle-id (:evidence-bundle-id wf)
                                 :status (:status wf)
                                 :completed-at (:completed-at wf)
                                 :has-evidence (some? (:evidence-bundle-id wf))}))
                            wfs)
        ;; Archived workflows (completed/failed only)
        archived (archive/get-archived-workflows state)
        archived-evidence (keep (fn [wf]
                                  (when (#{:completed :failed} (:status wf))
                                    {:source :archived
                                     :workflow-id (:id wf)
                                     :workflow-name (:name wf)
                                     :evidence-bundle-id (:evidence-bundle-id wf)
                                     :status (:status wf)
                                     :completed-at (:completed-at wf)
                                     :has-evidence (some? (:evidence-bundle-id wf))}))
                                archived)]
    {:trains train-evidence
     :workflows (vec (concat live-evidence archived-evidence))}))
