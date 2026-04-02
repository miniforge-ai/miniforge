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

   Budget state is persisted in ~/.miniforge/state/pr-monitor/ so that
   restarts do not reset counters."
  (:require
   [ai.miniforge.pr-lifecycle.monitor-config :as config]
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas + budget defaults

(def BudgetState
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

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 0
;; Budget state

(defn create-budget
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

;------------------------------------------------------------------------------ Layer 1
;; Budget operations

(defn record-fix-attempt
  "Record a fix attempt for a comment. Returns updated budget state."
  [budget comment-id]
  (-> budget
      (update-in [:comment-attempts comment-id] (fnil inc 0))
      (update :total-attempts inc)))

(defn record-question-answered
  "Record a question answered (does not consume fix budget)."
  [budget]
  (update budget :questions-answered inc))

(defn record-fix-pushed
  "Record a successful fix push."
  [budget]
  (update budget :fixes-pushed inc))

(defn comment-attempts-remaining
  "How many fix attempts remain for a specific comment."
  [budget comment-id]
  (let [max-per-comment (get-in budget [:limits :max-fix-attempts-per-comment])
        used (get-in budget [:comment-attempts comment-id] 0)]
    (max 0 (- max-per-comment used))))

(defn total-attempts-remaining
  "How many total fix attempts remain for the PR."
  [budget]
  (let [max-total (get-in budget [:limits :max-total-fix-attempts-per-pr])
        used (:total-attempts budget)]
    (max 0 (- max-total used))))

(defn hours-elapsed
  "Hours elapsed since monitoring started."
  [budget]
  (let [started (.getTime ^java.util.Date (:started-at budget))
        now (System/currentTimeMillis)]
    (/ (double (- now started)) 3600000.0)))

(defn time-remaining-hours
  "Hours remaining before abandon deadline."
  [budget]
  (let [max-hours (get-in budget [:limits :abandon-after-hours])]
    (max 0.0 (- (double max-hours) (hours-elapsed budget)))))

;------------------------------------------------------------------------------ Layer 1
;; Budget checks (hard stops)

(defn comment-budget-exhausted?
  "Check if the per-comment budget is exhausted."
  [budget comment-id]
  (zero? (comment-attempts-remaining budget comment-id)))

(defn pr-budget-exhausted?
  "Check if the total PR budget is exhausted."
  [budget]
  (zero? (total-attempts-remaining budget)))

(defn time-budget-exhausted?
  "Check if the time budget is exhausted."
  [budget]
  (<= (time-remaining-hours budget) 0.0))

(defn any-budget-exhausted?
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

(defn budget-summary
  "Generate a summary of current budget status for events and logging."
  [budget]
  {:total-attempts-used (:total-attempts budget)
   :total-attempts-remaining (total-attempts-remaining budget)
   :hours-elapsed (hours-elapsed budget)
   :hours-remaining (time-remaining-hours budget)
   :fixes-pushed (:fixes-pushed budget)
   :questions-answered (:questions-answered budget)
   :comment-attempts (:comment-attempts budget)})

;------------------------------------------------------------------------------ Layer 1
;; Budget persistence

(def ^:private state-dir
  (str (System/getProperty "user.home") "/.miniforge/state/pr-monitor"))

(defn save-budget!
  "Persist budget state to disk."
  [budget]
  (let [dir (io/file state-dir)
        _ (when-not (.exists dir) (.mkdirs dir))
        f (io/file state-dir (str "budget-" (:pr-number budget) ".edn"))]
    (spit f (pr-str budget))))

(defn load-budget
  "Load persisted budget for a PR, if it exists."
  [pr-number]
  (let [f (io/file state-dir (str "budget-" pr-number ".edn"))]
    (when (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception _e nil)))))

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
  (save-budget! b3)
  (load-budget 123)

  :leave-this-here)
