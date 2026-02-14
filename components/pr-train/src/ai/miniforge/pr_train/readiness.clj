(ns ai.miniforge.pr-train.readiness
  "Deterministic readiness scoring (0.0–1.0) for PR merge decisions.

   Replaces boolean `ready-to-merge?` with a weighted numeric score
   that accounts for dependency state, CI, approvals, gates, age, and staleness.

   Layer 0: Weights and thresholds
   Layer 1: Factor scoring functions
   Layer 2: Aggregation and explainability"
)

;------------------------------------------------------------------------------ Layer 0
;; Weights and thresholds

(def readiness-weights
  "Weights for each readiness factor. Must sum to 1.0."
  {:deps-merged       0.30
   :ci-passed         0.25
   :approved          0.20
   :gates-passed      0.15
   :age-penalty       0.05
   :staleness-penalty 0.05})

(def ^:const default-merge-threshold
  "Default readiness score required for merge."
  0.85)

(def ^:const max-age-days
  "PRs older than this incur full age penalty."
  14)

(def ^:const max-staleness-hours
  "PRs with no activity for this many hours incur full staleness penalty."
  72)

;------------------------------------------------------------------------------ Layer 1
;; Factor scoring functions — each returns 0.0 (bad) to 1.0 (good)

(defn score-deps-factor
  "Score based on how many dependencies are already merged.
   1.0 = all deps merged or no deps, 0.0 = no deps merged."
  [train pr]
  (let [deps (:pr/depends-on pr [])]
    (if (empty? deps)
      1.0
      (let [merged-prs (->> (:train/prs train)
                            (filter #(= :merged (:pr/status %)))
                            (map :pr/number)
                            set)
            merged-count (count (filter merged-prs deps))]
        (double (/ merged-count (count deps)))))))

(defn score-ci-factor
  "Score based on CI status. 1.0 = passed, 0.5 = running/pending, 0.0 = failed."
  [_train pr]
  (case (:pr/ci-status pr)
    :passed  1.0
    :running 0.5
    :pending 0.5
    :skipped 0.75
    :failed  0.0
    0.0))

(defn score-approved-factor
  "Score based on PR approval status.
   1.0 = approved/merged, 0.5 = reviewing, 0.0 = other."
  [_train pr]
  (case (:pr/status pr)
    :approved 1.0
    :merged   1.0
    :merging  1.0
    :reviewing 0.5
    :changes-requested 0.25
    0.0))

(defn score-gates-factor
  "Score based on gate pass rate.
   1.0 = all gates passed (or no gates), 0.0 = all gates failed."
  [_train pr]
  (let [gates (:pr/gate-results pr [])]
    (if (empty? gates)
      1.0
      (let [passed (count (filter :gate/passed? gates))]
        (double (/ passed (count gates)))))))

(defn score-age-factor
  "Score based on PR age. Older PRs score lower (freshness bonus).
   1.0 = brand new, 0.0 = older than max-age-days."
  [_train pr]
  (let [derived (:pr/derived-state pr)
        age-days (get derived :age-days 0)]
    (max 0.0 (- 1.0 (/ (double (min age-days max-age-days)) max-age-days)))))

(defn score-staleness-factor
  "Score based on time since last activity.
   1.0 = just updated, 0.0 = stale beyond max-staleness-hours."
  [_train pr]
  (let [derived (:pr/derived-state pr)
        staleness-hours (get derived :staleness-hours 0)]
    (max 0.0 (- 1.0 (/ (double (min staleness-hours max-staleness-hours)) max-staleness-hours)))))

;------------------------------------------------------------------------------ Layer 2
;; Aggregation and explainability

(def factor-fns
  "Map from factor keyword to scoring function."
  {:deps-merged       score-deps-factor
   :ci-passed         score-ci-factor
   :approved          score-approved-factor
   :gates-passed      score-gates-factor
   :age-penalty       score-age-factor
   :staleness-penalty score-staleness-factor})

(defn compute-readiness-score
  "Compute weighted readiness score for a PR in a train.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map

   Returns: Double in [0.0, 1.0]"
  [train pr]
  (reduce-kv
   (fn [total factor weight]
     (let [score-fn (get factor-fns factor)
           factor-score (score-fn train pr)]
       (+ total (* weight factor-score))))
   0.0
   readiness-weights))

(defn explain-readiness
  "Explain readiness score breakdown for a PR.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map

   Returns: {:readiness/score double
             :readiness/threshold double
             :readiness/ready? bool
             :readiness/factors [{:factor kw :weight double :score double :contribution double}]}"
  [train pr]
  (let [factors (mapv (fn [[factor weight]]
                        (let [score-fn (get factor-fns factor)
                              factor-score (score-fn train pr)
                              contribution (* weight factor-score)]
                          {:factor factor
                           :weight weight
                           :score factor-score
                           :contribution contribution}))
                      readiness-weights)
        total (reduce + 0.0 (map :contribution factors))]
    {:readiness/score total
     :readiness/threshold default-merge-threshold
     :readiness/ready? (>= total default-merge-threshold)
     :readiness/factors factors}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: score a PR with all deps merged, CI passed, approved
  (def example-train
    {:train/prs [{:pr/number 1 :pr/status :merged}
                 {:pr/number 2 :pr/status :approved
                  :pr/depends-on [1] :pr/ci-status :passed
                  :pr/gate-results [{:gate/passed? true}]}]})

  (def example-pr
    {:pr/number 2 :pr/status :approved
     :pr/depends-on [1] :pr/ci-status :passed
     :pr/gate-results [{:gate/passed? true}]
     :pr/derived-state {:age-days 2 :staleness-hours 6}})

  (compute-readiness-score example-train example-pr)
  (explain-readiness example-train example-pr)

  :leave-this-here)
