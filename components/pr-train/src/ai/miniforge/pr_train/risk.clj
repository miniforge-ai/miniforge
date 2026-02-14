(ns ai.miniforge.pr-train.risk
  "Explainable risk assessment for PRs with concrete factors.

   Scores PRs on 0.0–1.0 risk scale based on change size, dependency fanout,
   test coverage, author experience, review staleness, complexity, and
   critical file modifications.

   All thresholds are configurable via the `default-config` map. Callers can
   pass an override config to `assess-risk` to tune scoring without code changes.

   Layer 0: Configuration and risk factor definitions
   Layer 1: Factor assessment functions
   Layer 2: Aggregation and risk level classification")

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def default-config
  "Default risk assessment configuration. All thresholds and weights are
   tunable — pass overrides to `assess-risk` via the `:config` key."
  {:weights {:change-size        0.25
             :dependency-fanout  0.20
             :test-coverage-delta 0.15
             :author-experience  0.10
             :review-staleness   0.10
             :complexity-delta   0.10
             :critical-files     0.10}

   :levels {:critical 0.75
            :high     0.50
            :medium   0.25}

   :change-size {:thresholds [1000 500 200 50]
                 :scores     [1.0  0.75 0.50 0.25]}

   :dependency-fanout {:thresholds [5 3 1]
                       :scores     [1.0 0.75 0.50]
                       :exact-one  0.25}

   :test-coverage-delta {:thresholds [-10.0 -5.0 0.0 5.0]
                         :scores     [1.0   0.75 0.50 0.25]}

   :author-experience {:tiers [{:min-total 50 :min-recent 10 :score 0.0}
                               {:min-total 20 :min-recent 5  :score 0.25}
                               {:min-total 5  :min-recent 0  :score 0.50}
                               {:min-total 1  :min-recent 0  :score 0.75}]
                       :default 1.0}

   :review-staleness {:thresholds [168 72 24 4]
                      :scores     [1.0 0.75 0.50 0.25]}

   :complexity-delta {:thresholds [20 10 5 0]
                      :scores     [1.0 0.75 0.50 0.25]}

   :critical-files {:patterns [#"(?i)terraform.*state"
                               #"(?i)\.env"
                               #"(?i)credentials"
                               #"(?i)secrets?"
                               #"(?i)migration"
                               #"(?i)schema\.sql"
                               #"(?i)Dockerfile"
                               #"(?i)\.github/workflows"
                               #"(?i)ci\.ya?ml"]
                    :thresholds [3 1]
                    :scores     [1.0 0.75]
                    :exact-one  0.50}})

;; Derived public vars for backward compatibility and external use
(def risk-factors
  "Risk factor definitions with weights."
  (into {} (map (fn [[k v]] [k {:weight v :description (name k)}])
                (:weights default-config))))

(def risk-levels
  "Risk level thresholds."
  (:levels default-config))

;------------------------------------------------------------------------------ Layer 0
;; Threshold scoring helper

(defn- score-by-thresholds
  "Score a value against descending thresholds. Returns the score for the first
   threshold exceeded, or 0.0 if none match."
  [value thresholds scores compare-fn]
  (or (some (fn [[threshold score]]
              (when (compare-fn value threshold) score))
            (map vector thresholds scores))
      0.0))

;------------------------------------------------------------------------------ Layer 1
;; Factor assessment functions — each returns {:value any :score 0.0-1.0 :explanation str}

(defn- assess-change-size
  "Assess risk from change size. Larger changes = higher risk."
  [pr-data cfg]
  (let [{:keys [additions deletions]} (get pr-data :change-size {:additions 0 :deletions 0})
        total (+ (or additions 0) (or deletions 0))
        {:keys [thresholds scores]} (:change-size cfg)
        score (score-by-thresholds total thresholds scores >)]
    {:value {:additions additions :deletions deletions :total total}
     :score score
     :explanation (str total " lines changed"
                       (when (> total 500) " (large change)"))}))

(defn- assess-dependency-fanout
  "Assess risk from dependency fanout. More dependents = higher risk."
  [_train pr cfg]
  (let [blocks (:pr/blocks pr [])
        fanout (count blocks)
        {:keys [thresholds scores exact-one]} (:dependency-fanout cfg)
        score (if (= fanout 1)
                exact-one
                (score-by-thresholds fanout thresholds scores >))]
    {:value fanout
     :score score
     :explanation (str fanout " downstream PRs depend on this")}))

(defn- assess-test-coverage-delta
  "Assess risk from test coverage changes. Decreased coverage = higher risk."
  [pr-data cfg]
  (let [delta (get pr-data :test-coverage-delta 0.0)
        {:keys [thresholds scores]} (:test-coverage-delta cfg)
        score (score-by-thresholds delta thresholds scores <)]
    {:value delta
     :score score
     :explanation (if (neg? delta)
                    (str "Coverage decreased by " (Math/abs delta) "%")
                    (str "Coverage changed by " (if (pos? delta) "+" "") delta "%"))}))

(defn- assess-author-experience
  "Assess risk from author experience. Less experience = higher risk."
  [author-history cfg]
  (let [commits (get author-history :total-commits 0)
        recent (get author-history :recent-commits 0)
        {:keys [tiers default]} (:author-experience cfg)
        score (or (some (fn [{:keys [min-total min-recent score]}]
                          (when (and (> commits (or min-total 0))
                                     (> recent (or min-recent 0)))
                            score))
                        tiers)
                  default)]
    {:value {:total-commits commits :recent-commits recent}
     :score score
     :explanation (str commits " total commits, " recent " recent")}))

(defn- assess-review-staleness
  "Assess risk from review staleness. Stale reviews = higher risk."
  [pr-data cfg]
  (let [hours (get pr-data :hours-since-last-review 0)
        {:keys [thresholds scores]} (:review-staleness cfg)
        score (score-by-thresholds hours thresholds scores >)]
    {:value hours
     :score score
     :explanation (str hours " hours since last review")}))

(defn- assess-complexity-delta
  "Assess risk from complexity changes. Increased complexity = higher risk."
  [pr-data cfg]
  (let [delta (get pr-data :complexity-delta 0)
        {:keys [thresholds scores]} (:complexity-delta cfg)
        score (score-by-thresholds delta thresholds scores >)]
    {:value delta
     :score score
     :explanation (str "Complexity " (if (pos? delta) "increased" "changed")
                       " by " delta)}))

(defn- assess-critical-files
  "Assess risk from modifications to critical files."
  [pr-data cfg]
  (let [changed-files (get pr-data :changed-files [])
        patterns (:patterns (:critical-files cfg))
        critical (filter (fn [path]
                           (some #(re-find % path) patterns))
                         changed-files)
        count-critical (count critical)
        {:keys [thresholds scores exact-one]} (:critical-files cfg)
        score (if (= count-critical 1)
                exact-one
                (score-by-thresholds count-critical thresholds scores >))]
    {:value {:critical-files critical :count count-critical}
     :score score
     :explanation (if (pos? count-critical)
                    (str count-critical " critical file(s) modified")
                    "No critical files modified")}))

;------------------------------------------------------------------------------ Layer 2
;; Aggregation and risk level classification

(defn score->level
  "Convert risk score to risk level keyword."
  ([score] (score->level score (:levels default-config)))
  ([score levels]
   (cond
     (>= score (:critical levels)) :critical
     (>= score (:high levels))     :high
     (>= score (:medium levels))   :medium
     :else                         :low)))

(defn assess-risk
  "Assess overall risk for a PR.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map
   - pr-data - Map with :change-size, :test-coverage-delta, :changed-files,
               :hours-since-last-review, :complexity-delta
   - author-history - Map with :total-commits, :recent-commits
   - opts - Optional map with :config to override default-config

   Returns:
   {:risk/score float
    :risk/level :low|:medium|:high|:critical
    :risk/factors [{:factor kw :weight float :value any :score float :explanation str}]}"
  ([train pr pr-data author-history]
   (assess-risk train pr pr-data author-history {}))
  ([train pr pr-data author-history opts]
   (let [cfg (merge default-config (:config opts))
         weights (:weights cfg)
         assessments {:change-size        (assess-change-size pr-data cfg)
                      :dependency-fanout  (assess-dependency-fanout train pr cfg)
                      :test-coverage-delta (assess-test-coverage-delta pr-data cfg)
                      :author-experience  (assess-author-experience author-history cfg)
                      :review-staleness   (assess-review-staleness pr-data cfg)
                      :complexity-delta   (assess-complexity-delta pr-data cfg)
                      :critical-files     (assess-critical-files pr-data cfg)}
         factors (mapv (fn [[factor-key weight]]
                         (let [{:keys [value score explanation]} (get assessments factor-key)]
                           {:factor factor-key
                            :weight weight
                            :value value
                            :score score
                            :explanation explanation}))
                       weights)
         total-score (reduce + 0.0
                             (map (fn [{:keys [weight score]}]
                                    (* weight score))
                                  factors))]
     {:risk/score total-score
      :risk/level (score->level total-score (:levels cfg))
      :risk/factors factors})))

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

  ;; With custom config — stricter thresholds
  (assess-risk
   {:train/prs []}
   {:pr/number 1 :pr/blocks []}
   {:change-size {:additions 100 :deletions 50}}
   {}
   {:config {:change-size {:thresholds [500 200 100 25]
                           :scores     [1.0 0.75 0.50 0.25]}}})

  :leave-this-here)
