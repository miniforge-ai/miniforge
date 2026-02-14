(ns ai.miniforge.pr-train.risk
  "Explainable risk assessment for PRs with concrete factors.

   Scores PRs on 0.0–1.0 risk scale based on change size, dependency fanout,
   test coverage, author experience, review staleness, complexity, and
   critical file modifications.

   Layer 0: Risk factors and thresholds
   Layer 1: Factor assessment functions
   Layer 2: Aggregation and risk level classification")

;------------------------------------------------------------------------------ Layer 0
;; Risk factors and thresholds

(def risk-factors
  "Risk factor definitions with weights."
  {:change-size        {:weight 0.25 :description "Lines added/removed"}
   :dependency-fanout  {:weight 0.20 :description "Number of dependent PRs"}
   :test-coverage-delta {:weight 0.15 :description "Change in test coverage"}
   :author-experience  {:weight 0.10 :description "Author's familiarity with codebase"}
   :review-staleness   {:weight 0.10 :description "Time since last review activity"}
   :complexity-delta   {:weight 0.10 :description "Change in cyclomatic complexity"}
   :critical-files     {:weight 0.10 :description "Modifications to critical files"}})

(def risk-levels
  "Risk level thresholds."
  {:critical 0.75
   :high     0.50
   :medium   0.25})

(def ^:private critical-file-patterns
  "File patterns considered critical (high risk when modified)."
  [#"(?i)terraform.*state"
   #"(?i)\.env"
   #"(?i)credentials"
   #"(?i)secrets?"
   #"(?i)migration"
   #"(?i)schema\.sql"
   #"(?i)Dockerfile"
   #"(?i)\.github/workflows"
   #"(?i)ci\.ya?ml"])

;------------------------------------------------------------------------------ Layer 1
;; Factor assessment functions — each returns {:value any :score 0.0-1.0 :explanation str}

(defn- assess-change-size
  "Assess risk from change size. Larger changes = higher risk."
  [pr-data]
  (let [{:keys [additions deletions]} (get pr-data :change-size {:additions 0 :deletions 0})
        total (+ (or additions 0) (or deletions 0))
        score (cond
                (> total 1000) 1.0
                (> total 500)  0.75
                (> total 200)  0.50
                (> total 50)   0.25
                :else          0.0)]
    {:value {:additions additions :deletions deletions :total total}
     :score score
     :explanation (str total " lines changed"
                       (when (> total 500) " (large change)"))}))

(defn- assess-dependency-fanout
  "Assess risk from dependency fanout. More dependents = higher risk."
  [_train pr]
  (let [blocks (:pr/blocks pr [])
        fanout (count blocks)
        score (cond
                (> fanout 5) 1.0
                (> fanout 3) 0.75
                (> fanout 1) 0.50
                (= fanout 1) 0.25
                :else        0.0)]
    {:value fanout
     :score score
     :explanation (str fanout " downstream PRs depend on this")}))

(defn- assess-test-coverage-delta
  "Assess risk from test coverage changes. Decreased coverage = higher risk."
  [pr-data]
  (let [delta (get pr-data :test-coverage-delta 0.0)
        score (cond
                (< delta -10.0) 1.0
                (< delta -5.0)  0.75
                (< delta 0.0)   0.50
                (< delta 5.0)   0.25
                :else           0.0)]
    {:value delta
     :score score
     :explanation (if (neg? delta)
                    (str "Coverage decreased by " (Math/abs delta) "%")
                    (str "Coverage changed by " (if (pos? delta) "+" "") delta "%"))}))

(defn- assess-author-experience
  "Assess risk from author experience. Less experience = higher risk."
  [author-history]
  (let [commits (get author-history :total-commits 0)
        recent (get author-history :recent-commits 0)
        score (cond
                (and (> commits 50) (> recent 10)) 0.0
                (and (> commits 20) (> recent 5))  0.25
                (> commits 5)                       0.50
                (> commits 0)                       0.75
                :else                               1.0)]
    {:value {:total-commits commits :recent-commits recent}
     :score score
     :explanation (str commits " total commits, " recent " recent")}))

(defn- assess-review-staleness
  "Assess risk from review staleness. Stale reviews = higher risk."
  [pr-data]
  (let [hours (get pr-data :hours-since-last-review 0)
        score (cond
                (> hours 168) 1.0    ; > 1 week
                (> hours 72)  0.75   ; > 3 days
                (> hours 24)  0.50   ; > 1 day
                (> hours 4)   0.25   ; > 4 hours
                :else         0.0)]
    {:value hours
     :score score
     :explanation (str hours " hours since last review")}))

(defn- assess-complexity-delta
  "Assess risk from complexity changes. Increased complexity = higher risk."
  [pr-data]
  (let [delta (get pr-data :complexity-delta 0)
        score (cond
                  (> delta 20) 1.0
                  (> delta 10) 0.75
                  (> delta 5)  0.50
                  (> delta 0)  0.25
                  :else        0.0)]
      {:value delta
       :score score
       :explanation (str "Complexity " (if (pos? delta) "increased" "changed")
                         " by " delta)}))

(defn- assess-critical-files
  "Assess risk from modifications to critical files."
  [pr-data]
  (let [changed-files (get pr-data :changed-files [])
        critical (filter (fn [path]
                           (some #(re-find % path) critical-file-patterns))
                         changed-files)
        count-critical (count critical)
        score (cond
                (> count-critical 3) 1.0
                (> count-critical 1) 0.75
                (= count-critical 1) 0.50
                :else                0.0)]
    {:value {:critical-files critical :count count-critical}
     :score score
     :explanation (if (pos? count-critical)
                    (str count-critical " critical file(s) modified")
                    "No critical files modified")}))

;------------------------------------------------------------------------------ Layer 2
;; Aggregation and risk level classification

(defn score->level
  "Convert risk score to risk level keyword."
  [score]
  (cond
    (>= score (:critical risk-levels)) :critical
    (>= score (:high risk-levels))     :high
    (>= score (:medium risk-levels))   :medium
    :else                              :low))

(defn assess-risk
  "Assess overall risk for a PR.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map
   - pr-data - Map with :change-size, :test-coverage-delta, :changed-files,
               :hours-since-last-review, :complexity-delta
   - author-history - Map with :total-commits, :recent-commits

   Returns:
   {:risk/score float
    :risk/level :low|:medium|:high|:critical
    :risk/factors [{:factor kw :weight float :value any :score float :explanation str}]}"
  [train pr pr-data author-history]
  (let [assessments {:change-size       (assess-change-size pr-data)
                     :dependency-fanout  (assess-dependency-fanout train pr)
                     :test-coverage-delta (assess-test-coverage-delta pr-data)
                     :author-experience  (assess-author-experience author-history)
                     :review-staleness   (assess-review-staleness pr-data)
                     :complexity-delta   (assess-complexity-delta pr-data)
                     :critical-files     (assess-critical-files pr-data)}
        factors (mapv (fn [[factor-key {:keys [weight]}]]
                        (let [{:keys [value score explanation]} (get assessments factor-key)]
                          {:factor factor-key
                           :weight weight
                           :value value
                           :score score
                           :explanation explanation}))
                      risk-factors)
        total-score (reduce + 0.0
                            (map (fn [{:keys [weight score]}]
                                   (* weight score))
                                 factors))]
    {:risk/score total-score
     :risk/level (score->level total-score)
     :risk/factors factors}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example risk assessment
  (assess-risk
   {:train/prs []}
   {:pr/number 1 :pr/blocks [2 3 4]}
   {:change-size {:additions 600 :deletions 100}
    :test-coverage-delta -3.0
    :changed-files ["main.tf" "Dockerfile" "schema.sql"]
    :hours-since-last-review 48
    :complexity-delta 8}
   {:total-commits 15 :recent-commits 3})

  :leave-this-here)
