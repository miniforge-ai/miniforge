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

(ns ai.miniforge.tui-views.view.project.supervisory
  "Supervisory projection builders — N5-delta §3-5.

   Pure functions: (model) -> stable data shapes for the monitor zones.
   Each projection returns a stable shape when the model is empty —
   no nil returns, no exceptions.

   Layer 0: Governance state derivation (§4).
   Layer 1: Monitor zone projection builders (§5).
   Layer 2: Attention derivation rules (§5)."
  )

;------------------------------------------------------------------------------ Layer 0
;; PR Governance state derivation — N5-delta §4

(defn derive-governance-state
  "Derive the governance state for a PR from its evaluations and waivers.

   States per N5-delta §4:
   :not-evaluated  — no PolicyEvaluation exists for this PR
   :policy-passing — latest evaluation result is :pass
   :policy-failing — latest evaluation result is :fail, no Waiver
   :waived         — latest evaluation result is :fail, Waiver exists
   :escalated      — PR has been explicitly escalated

   Arguments:
     pr-id       - any identifier used to match evaluations [:repo num] etc.
     evaluations - seq of {:eval/id ... :eval/pr-id ... :eval/result ... :eval/evaluated-at ...}
     waivers     - seq of {:waiver/id ... :waiver/eval-id ...}

   Returns a keyword."
  [pr-id evaluations waivers]
  (let [pr-evals (filter #(= pr-id (:eval/pr-id %)) (or evaluations []))
        latest   (when (seq pr-evals)
                   (apply max-key
                          #(inst-ms (or (:eval/evaluated-at %) (java.util.Date. 0)))
                          pr-evals))]
    (cond
      (nil? latest)
      :not-evaluated

      ;; Check for explicit escalation flag before result check
      (:eval/escalated? latest)
      :escalated

      (= :pass (:eval/result latest))
      :policy-passing

      ;; result is :fail — check for waiver
      (some #(= (:eval/id latest) (:waiver/eval-id %)) (or waivers []))
      :waived

      :else
      :policy-failing)))

;------------------------------------------------------------------------------ Layer 1
;; Monitor zone projection builders

(defn- duration-str
  "Format elapsed milliseconds as a compact human string (e.g. '3m', '1h2m')."
  [ms]
  (when (and ms (pos? ms))
    (let [s  (quot ms 1000)
          m  (quot s 60)
          h  (quot m 60)
          rm (mod m 60)]
      (cond
        (>= h 1) (str h "h" (when (pos? rm) (str rm "m")))
        (>= m 1) (str m "m")
        :else    (str s "s")))))

(defn- elapsed-ms
  "Compute elapsed milliseconds from started-at to now. nil if no started-at."
  [started-at]
  (when started-at
    (- (System/currentTimeMillis)
       (if (inst? started-at)
         (inst-ms started-at)
         0))))

(defn workflow-ticker
  "Project workflow runs for the monitor ticker zone.

   Returns a vector of rows sorted active-first, then by started-at descending.
   Each row: {:id ... :key ... :status ... :phase ... :duration ... :agent-msg ...}

   Always returns a vector (empty if no workflows)."
  [model]
  (let [wfs (get model :workflows [])]
    (if (empty? wfs)
      []
      (let [active?   (fn [wf] (= :running (or (:status wf) :unknown)))
            by-start  (fn [wf] (inst-ms (or (:started-at wf) (java.util.Date. 0))))
            sorted    (sort-by (juxt (comp not active?) (comp - by-start)) wfs)]
        (mapv (fn [wf]
                {:id       (:id wf)
                 :key      (or (:name wf) (some-> (:id wf) str (subs 0 8)) "")
                 :status   (or (:status wf) :unknown)
                 :phase    (some-> (:phase wf) name)
                 :duration (duration-str (or (:duration-ms wf)
                                             (elapsed-ms (:started-at wf))))
                 :agent-msg (some-> (first (vals (:agents wf {})))
                                    :message
                                    (or ""))})
              sorted)))))

(defn pr-train-strip
  "Project PR train and fleet summary for the monitor zone.

   Returns:
   {:train-active?   bool
    :train-merged    int    — merged PRs in active train
    :train-total     int    — total PRs in active train
    :fleet-open      int    — total open PRs across fleet
    :fleet-ready     int    — PRs with readiness/ready? true
    :fleet-monitored int    — PRs actively monitored by pr-monitor loop}"
  [model]
  (let [prs        (get model :pr-items [])
        train      (some #(when (= (:active-train-id model) (:train/id %)) %)
                         (get model :trains []))
        progress   (get train :train/progress {})
        monitored  (->> prs
                        (filter #(get % :pr/monitor-active?))
                        count)]
    {:train-active?   (some? train)
     :train-merged    (get progress :merged 0)
     :train-total     (get progress :total 0)
     :fleet-open      (count prs)
     :fleet-ready     (count (filter #(get-in % [:pr/readiness :readiness/ready?]) prs))
     :fleet-monitored monitored}))

(defn policy-health
  "Project policy health summary for the monitor zone.

   Returns:
   {:pass-rate              float   — 0.0-1.0 (1.0 when no evals)
    :total-evaluations      int
    :passing-evaluations    int
    :violations-by-category {category-str -> count}
    :governance-counts      {:not-evaluated ... :policy-passing ... etc.}}"
  [model]
  (let [prs         (get model :pr-items [])
        evaluations (get model :policy-evaluations [])
        waivers     (get model :waivers [])]
    (if (empty? prs)
      {:pass-rate           1.0
       :total-evaluations   0
       :passing-evaluations 0
       :violations-by-category {}
       :governance-counts   {:not-evaluated   0
                             :policy-passing  0
                             :policy-failing  0
                             :waived          0
                             :escalated       0}}
      (let [pr-ids     (mapv #(vector (:pr/repo %) (:pr/number %)) prs)
            gov-states (mapv #(derive-governance-state % evaluations waivers) pr-ids)
            gov-counts (frequencies gov-states)
            pass-evals (count (filter #(= :pass (:eval/result %)) evaluations))
            total      (count evaluations)
            all-viols  (mapcat :eval/violations evaluations)
            by-cat     (frequencies (map :violation/category all-viols))]
        {:pass-rate           (if (zero? total) 1.0 (double (/ pass-evals total)))
         :total-evaluations   total
         :passing-evaluations pass-evals
         :violations-by-category (into {} (map (fn [[k v]] [(or k "unknown") v]) by-cat))
         :governance-counts   (merge {:not-evaluated 0 :policy-passing 0
                                      :policy-failing 0 :waived 0 :escalated 0}
                                     gov-counts)}))))

;------------------------------------------------------------------------------ Layer 2
;; Attention derivation — N5-delta §5

(defn- workflow-failed-attention
  "Derive attention items from failed workflows."
  [workflows]
  (for [wf workflows
        :when (= :failed (:status wf))]
    {:attention/id          (random-uuid)
     :attention/severity    :critical
     :attention/summary     (str "Workflow failed: " (or (:name wf) (str (:id wf))))
     :attention/source-type :workflow
     :attention/source-id   (:id wf)
     :attention/created-at  (:last-updated wf)}))

(defn- budget-exhausted-attention
  "Derive attention items from budget-exhausted PR monitor events."
  [pr-items]
  (for [pr pr-items
        :when (:pr/monitor-budget-exhausted? pr)]
    {:attention/id          (random-uuid)
     :attention/severity    :critical
     :attention/summary     (str "Budget exhausted: " (:pr/repo pr) "#" (:pr/number pr))
     :attention/source-type :pr-monitor
     :attention/source-id   [(:pr/repo pr) (:pr/number pr)]
     :attention/created-at  nil}))

(defn- budget-warning-attention
  "Derive attention items from budget-warning PR monitor events."
  [pr-items]
  (for [pr pr-items
        :when (:pr/monitor-budget-warning? pr)]
    {:attention/id          (random-uuid)
     :attention/severity    :warning
     :attention/summary     (str "Budget warning: " (:pr/repo pr) "#" (:pr/number pr))
     :attention/source-type :pr-monitor
     :attention/source-id   [(:pr/repo pr) (:pr/number pr)]
     :attention/created-at  nil}))

(defn- escalated-attention
  "Derive attention items from escalated PRs."
  [pr-items]
  (for [pr pr-items
        :when (:pr/monitor-escalated? pr)]
    {:attention/id          (random-uuid)
     :attention/severity    :critical
     :attention/summary     (str "Escalated: " (:pr/repo pr) "#" (:pr/number pr))
     :attention/source-type :pr-monitor
     :attention/source-id   [(:pr/repo pr) (:pr/number pr)]
     :attention/created-at  nil}))

(defn- explicit-attention
  "Return explicitly tracked attention items from model."
  [model]
  (get model :attention-items []))

(defn- severity-order
  "Sort order for severity — critical first."
  [item]
  (case (:attention/severity item)
    :critical 0
    :warning  1
    :info     2
    3))

(defn attention
  "Derive the current attention items from model state (§5).

   Sources:
   - Failed workflows
   - PR monitor budget exhausted/warning events
   - PR monitor escalations
   - Explicit :attention-items in model

   Returns sorted vector (critical first) of attention item maps.
   Always returns a vector."
  [model]
  (let [workflows  (get model :workflows [])
        pr-items   (get model :pr-items [])
        items      (concat
                    (workflow-failed-attention workflows)
                    (budget-exhausted-attention pr-items)
                    (escalated-attention pr-items)
                    (budget-warning-attention pr-items)
                    (explicit-attention model))]
    (vec (sort-by severity-order items))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Governance state derivation
  (derive-governance-state [:org/repo 42] [] [])
  ;; => :not-evaluated

  (derive-governance-state [:org/repo 42]
    [{:eval/id (random-uuid) :eval/pr-id [:org/repo 42] :eval/result :pass
      :eval/evaluated-at (java.util.Date.)}]
    [])
  ;; => :policy-passing

  ;; Workflow ticker with empty model
  (workflow-ticker {:workflows []})
  ;; => []

  ;; Policy health with empty model
  (policy-health {})
  ;; => {:pass-rate 1.0 ...}

  ;; Attention with empty model
  (attention {})
  ;; => []

  :leave-this-here)
