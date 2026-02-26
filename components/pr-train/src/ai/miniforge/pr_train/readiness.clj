(ns ai.miniforge.pr-train.readiness
  "Deterministic readiness scoring (0.0–1.0) for PR merge decisions.

   Replaces boolean `ready-to-merge?` with a weighted numeric score
   that accounts for dependency state, CI, approvals, gates, age, and staleness.

   All scoring parameters are loaded from resources/config/governance/readiness.edn
   and can be overridden at call time by passing a custom config.

   Layer 0: Configuration (data)
   Layer 1: Factor scoring functions
   Layer 2: Aggregation and explainability"
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration — all tunable data in one place

(def default-config
  "Default readiness scoring configuration loaded from resources/config/governance/readiness.edn.
   All weights, thresholds, and score maps are pure data. Override by passing
   a custom config to `compute-readiness-score` and `explain-readiness`."
  (config/load-governance-config :readiness))

;; Backward-compatible aliases
(def readiness-weights (:weights default-config))
(def ^:const default-merge-threshold (:merge-threshold default-config))

;------------------------------------------------------------------------------ Layer 1
;; Factor scoring functions — each returns 0.0 (bad) to 1.0 (good)

(defn score-deps-factor
  "Score based on how many dependencies are already merged.
   1.0 = all deps merged or no deps, 0.0 = no deps merged."
  [train pr _cfg]
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
  "Score based on CI status. Scores looked up from :ci-scores config."
  [_train pr cfg]
  (get (:ci-scores cfg) (:pr/ci-status pr) 0.0))

(defn score-approved-factor
  "Score based on PR approval status. Scores looked up from :approval-scores config."
  [_train pr cfg]
  (get (:approval-scores cfg) (:pr/status pr) 0.0))

(defn score-gates-factor
  "Score based on gate pass rate.
   1.0 = all gates passed (or no gates), 0.0 = all gates failed."
  [_train pr _cfg]
  (let [gates (:pr/gate-results pr [])]
    (if (empty? gates)
      1.0
      (let [passed (count (filter :gate/passed? gates))]
        (double (/ passed (count gates)))))))

(defn score-behind-main-factor
  "Score based on whether the PR branch is behind main.
   1.0 = not behind main (CLEAN), 0.0 = behind main (BEHIND/DIRTY)."
  [_train pr _cfg]
  (if (:pr/behind-main? pr) 0.0 1.0))

;------------------------------------------------------------------------------ Layer 2
;; Aggregation and explainability

(def factor-fns
  "Map from factor keyword to scoring function."
  {:deps-merged       score-deps-factor
   :ci-passed         score-ci-factor
   :approved          score-approved-factor
   :gates-passed      score-gates-factor
   :behind-main       score-behind-main-factor})

(defn- score-factor
  "Score a single factor, returning its contribution map."
  [train pr cfg [factor weight]]
  (let [score-fn (get factor-fns factor)
        score (score-fn train pr cfg)]
    {:factor factor
     :weight weight
     :score score
     :contribution (* weight score)}))

(defn compute-readiness-score
  "Compute weighted readiness score for a PR in a train.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map
   - config - Optional config map to override `default-config`

   Returns: Double in [0.0, 1.0]"
  ([train pr] (compute-readiness-score train pr {}))
  ([train pr config]
   (let [cfg (merge default-config config)]
     (->> (:weights cfg)
          (map (partial score-factor train pr cfg))
          (transduce (map :contribution) + 0.0)))))

(defn explain-readiness
  "Explain readiness score breakdown for a PR.

   Arguments:
   - train - PRTrain map
   - pr - TrainPR map
   - config - Optional config map to override `default-config`

   Returns: {:readiness/score double
             :readiness/threshold double
             :readiness/ready? bool
             :readiness/factors [{:factor kw :weight double :score double :contribution double}]}"
  ([train pr] (explain-readiness train pr {}))
  ([train pr config]
   (let [cfg (merge default-config config)
         factors (mapv (partial score-factor train pr cfg) (:weights cfg))
         total (transduce (map :contribution) + 0.0 factors)
         threshold (:merge-threshold cfg)]
     {:readiness/score total
      :readiness/threshold threshold
      :readiness/ready? (>= total threshold)
      :readiness/factors factors})))

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
     :pr/behind-main? false})

  (compute-readiness-score example-train example-pr)
  (explain-readiness example-train example-pr)

  :leave-this-here)
