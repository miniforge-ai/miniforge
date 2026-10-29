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
(ns ai.miniforge.pr-lifecycle.monitor-budget
  "Per-PR budget tracking for the PR monitor loop.

   Tracks fix attempts per comment, total attempts per PR, and time bounds.
   Budget limits are hard stops — when exhausted, all autonomous action
  ceases and an escalation event is emitted.

   Budget state is persisted per repository and PR (see monitor-store) so
   that restarts do not reset counters. A budget file that cannot be read
   loads as an exhausted budget, never as a fresh one."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.pr-lifecycle.messages :as msg]
   [ai.miniforge.pr-lifecycle.monitor-config :as config]
   [ai.miniforge.pr-lifecycle.monitor-store :as store]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas + budget defaults
(def ^{:stratum 0} BudgetState
  [:map
   [:pr-number pos-int?]
   [:limits [:map
             [:max-fix-attempts-per-comment pos-int?]
             [:max-total-fix-attempts-per-pr pos-int?]
             [:abandon-after-hours pos-int?]]]
   [:comment-attempts [:map-of int? nat-int?]]
   [:total-attempts nat-int?]
   [:started-at inst?]
   [:questions-answered nat-int?]
   [:fixes-pushed nat-int?]])

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate result-schema value))

;; Budget operations
(defn ^{:stratum 0} record-fix-attempt
  "Record a fix attempt for a comment. Returns updated budget state."
  [budget comment-id]
  (-> budget
      (update-in [:comment-attempts comment-id] (fnil inc 0))
      (update :total-attempts inc)))

(defn ^{:stratum 0} record-question-answered
  "Record a question answered (does not consume fix budget)."
  [budget]
  (update budget :questions-answered inc))

(defn ^{:stratum 0} record-fix-pushed
  "Record a successful fix push."
  [budget]
  (update budget :fixes-pushed inc))

(defn ^{:stratum 0} comment-attempts-remaining
  "How many fix attempts remain for a specific comment."
  [budget comment-id]
  (let [max-per-comment (get-in budget [:limits :max-fix-attempts-per-comment])
        used (get-in budget [:comment-attempts comment-id] 0)]
    (max 0 (- max-per-comment used))))

(defn ^{:stratum 0} total-attempts-remaining
  "How many total fix attempts remain for the PR."
  [budget]
  (let [max-total (get-in budget [:limits :max-total-fix-attempts-per-pr])
        used (:total-attempts budget)]
    (max 0 (- max-total used))))

(defn ^{:stratum 0} hours-elapsed
  "Hours elapsed since monitoring started."
  [budget]
  (let [started (.getTime ^java.util.Date (:started-at budget))
        now (System/currentTimeMillis)]
    (/ (double (- now started)) 3600000.0)))

;; Budget persistence
(defn ^{:stratum 0} budget-file-name
  "Name of PR `pr-number`'s budget file inside its repo's state directory."
  [pr-number]
  (str "budget-" pr-number ".edn"))

(defn ^{:stratum 0} invalid-budget
  [file pr-number]
  (anomaly/anomaly :invalid-input
                   (msg/system-t :monitor-state/invalid-budget {:path (str file) :pr-number pr-number})
                   {:monitor-state/path (str file)}))

(defn ^{:stratum 0} exhausted-budget
  "A budget that fails every check, standing in for one that could not be
   read. `load-error` says why and reaches the escalation event through
   `budget-summary`. Loading this, not a fresh budget, keeps the hard stop
   when a budget file is damaged."
  [pr-number load-error]
  (let [limits (config/budget-defaults)]
    {:pr-number pr-number :limits limits :comment-attempts {}
     :total-attempts (:max-total-fix-attempts-per-pr limits)
     :started-at (java.util.Date. 0) :questions-answered 0 :fixes-pushed 0
     :load-error load-error}))

(defn ^{:stratum 0} merge-budgets
  "`mine` with every counter at least its value in `on-disk`, and the earlier
   start time, so a monitor saving a stale copy cannot lower what another
   saved. Attempts made at once by two monitors on one PR are not summed."
  [on-disk mine]
  (if-not on-disk
    mine
    (-> (merge-with max (select-keys on-disk [:total-attempts :questions-answered :fixes-pushed]) mine)
        (update :comment-attempts #(merge-with max (:comment-attempts on-disk) %))
        (update :started-at #(if (neg? (compare (:started-at on-disk) %)) (:started-at on-disk) %)))))

;------------------------------------------------------------------------------ Layer 1

;; Budget state
(defn ^{:stratum 1} create-budget
  "Create a new budget tracker for a PR.

   Arguments:
   - pr-number: PR number
   - config: Optional budget config overrides

   Returns budget state map."
  [pr-number & [config]]
  (let [limits (merge (config/budget-defaults) config)]
    (validate!
     BudgetState
     {:pr-number pr-number
      :limits limits
      :comment-attempts {}
      :total-attempts 0
      :started-at (java.util.Date.)
      :questions-answered 0
      :fixes-pushed 0})))

(defn ^{:stratum 1} time-remaining-hours
  "Hours remaining before abandon deadline."
  [budget]
  (let [max-hours (get-in budget [:limits :abandon-after-hours])]
    (max 0.0 (- (double max-hours) (hours-elapsed budget)))))

;; Budget checks (hard stops)
(defn ^{:stratum 1} comment-budget-exhausted?
  "Check if the per-comment budget is exhausted."
  [budget comment-id]
  (zero? (comment-attempts-remaining budget comment-id)))

(defn ^{:stratum 1} pr-budget-exhausted?
  "Check if the total PR budget is exhausted."
  [budget]
  (zero? (total-attempts-remaining budget)))

(defn ^{:stratum 1} valid-budget?
  "True when `persisted` is a BudgetState for PR `pr-number`."
  [persisted pr-number]
  (and (schema/valid? BudgetState persisted)
       (= pr-number (:pr-number persisted))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} time-budget-exhausted?
  "Check if the time budget is exhausted."
  [budget]
  (<= (time-remaining-hours budget) 0.0))

(defn ^{:stratum 2} budget-summary
  "Generate a summary of current budget status for events and logging."
  [budget]
  (cond-> {:total-attempts-used (:total-attempts budget)
           :total-attempts-remaining (total-attempts-remaining budget)
           :hours-elapsed (hours-elapsed budget)
           :hours-remaining (time-remaining-hours budget)
           :fixes-pushed (:fixes-pushed budget)
           :questions-answered (:questions-answered budget)
           :comment-attempts (:comment-attempts budget)}
    (:load-error budget) (assoc :load-error (:load-error budget))))

(defn ^{:stratum 2} load-budget
  "The budget saved for PR `pr-number` of repo `repo-slug` (owner/name); nil
   when none was saved; `exhausted-budget` when the slug is not owner/name or
   the file does not hold this PR's budget. The file is left as it is."
  [repo-slug pr-number]
  (let [file   (store/repo-file repo-slug (budget-file-name pr-number))
        loaded (if file (store/read-map file) (store/no-repo repo-slug))]
    (cond
      (nil? loaded)                    nil
      (valid-budget? loaded pr-number) loaded
      :else (exhausted-budget pr-number (:anomaly/message (if (anomaly/anomaly? loaded)
                                                            loaded
                                                            (invalid-budget file pr-number)))))))

(defn ^{:stratum 2} save-budget!
  "Merge `budget` into the copy saved for its PR in repo `repo-slug` (see
   `merge-budgets`) under the file lock, and write it atomically. Returns the
   budget now on disk, or an anomaly with nothing written for a stand-in from
   `exhausted-budget`, a slug that is not owner/name, or a file that does not
   hold this PR's budget."
  [repo-slug budget]
  (let [pr-number (:pr-number budget)
        file      (store/repo-file repo-slug (budget-file-name pr-number))
        saved     (cond
                    (:load-error budget) (anomaly/anomaly :conflict
                                                          (msg/system-t :monitor-state/budget-held {:pr-number pr-number})
                                                          {:pr-number pr-number})
                    (nil? file)          (store/no-repo repo-slug)
                    :else                (store/update-file! file #(if (or (nil? %) (valid-budget? % pr-number))
                                                                     (merge-budgets % budget)
                                                                     %)))]
    (if (or (anomaly/anomaly? saved) (valid-budget? saved pr-number))
      saved
      (invalid-budget file pr-number))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} any-budget-exhausted?
  "Check if any budget dimension is exhausted.

   Returns nil if budget OK, or a keyword indicating which limit was hit:
   :comment-limit, :pr-limit, or :time-limit."
  [budget comment-id]
  (cond
    (time-budget-exhausted? budget)                     :time-limit
    (pr-budget-exhausted? budget)                       :pr-limit
    (and comment-id
         (comment-budget-exhausted? budget comment-id)) :comment-limit
    :else                                               nil))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create budget
  (def b (create-budget 123))

  ;; Record attempts
  (def b2 (-> b
               (record-fix-attempt 456)
               (record-fix-attempt 456)
               (record-fix-attempt 789)))

  ;; Check limits
  (comment-attempts-remaining b2 456) ; => 1
  (total-attempts-remaining b2)       ; => 7
  (any-budget-exhausted? b2 456)      ; => nil

  ;; After 3 attempts on same comment
  (def b3 (record-fix-attempt b2 456))
  (comment-budget-exhausted? b3 456)  ; => true
  (any-budget-exhausted? b3 456)      ; => :comment-limit

  ;; Summary
  (budget-summary b3)

  ;; Persistence
  (save-budget! "miniforge-ai/miniforge" b3)
  (load-budget "miniforge-ai/miniforge" 123)

  :leave-this-here)
