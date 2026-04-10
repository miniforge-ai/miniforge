;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.signal
  "Signal extraction from observer metrics, reliability state, and training data.

   Signals are the raw inputs to the diagnosis engine. Each signal represents
   a detectable anomaly or pattern that may warrant adaptation.

   Layer 0: Signal types and extraction (pure functions)")

;------------------------------------------------------------------------------ Layer 0
;; Signal extraction

(defn- extract-slo-breach-signals
  "Extract signals from SLO breaches."
  [slo-checks]
  (->> slo-checks
       (filter :breached?)
       (mapv (fn [{:keys [sli/name slo/target slo/actual slo/tier]}]
               {:signal/type :slo-breach
                :signal/severity (if (= :critical tier) :critical :high)
                :signal/evidence {:sli-name name
                                  :target target
                                  :actual actual
                                  :tier tier}
                :signal/message (format "SLO breach: %s at %.2f (target %.2f) for %s tier"
                                        (clojure.core/name name) actual target (clojure.core/name tier))}))))

(defn- extract-failure-pattern-signals
  "Extract signals from recurring failure patterns."
  [failure-events min-count]
  (let [by-class (->> failure-events
                      (map :failure/class)
                      (filter some?)
                      frequencies)]
    (->> by-class
         (filter (fn [[_ cnt]] (>= cnt min-count)))
         (mapv (fn [[cls cnt]]
                 {:signal/type :recurring-failure
                  :signal/severity (if (>= cnt (* 2 min-count)) :high :medium)
                  :signal/evidence {:failure-class cls :count cnt}
                  :signal/message (format "Recurring failure: %s occurred %d times"
                                          (clojure.core/name cls) cnt)})))))

(defn- extract-quality-regression-signals
  "Extract signals from declining training example quality."
  [training-examples min-examples]
  (when (>= (count training-examples) (* 2 min-examples))
    (let [sorted (sort-by :training/timestamp training-examples)
          half (quot (count sorted) 2)
          first-half (take half sorted)
          second-half (drop half sorted)
          avg-quality (fn [exs]
                        (let [scores (map #(get-in % [:training/labels :quality-score]) exs)
                              valid (filter some? scores)]
                          (if (seq valid)
                            (/ (reduce + valid) (count valid))
                            0.0)))
          q1 (avg-quality first-half)
          q2 (avg-quality second-half)
          regression (- q1 q2)]
      (when (> regression 0.1) ; 10% quality drop
        [{:signal/type :quality-regression
          :signal/severity (if (> regression 0.2) :high :medium)
          :signal/evidence {:first-half-quality q1
                            :second-half-quality q2
                            :regression regression}
          :signal/message (format "Quality regression: %.2f → %.2f (%.0f%% drop)"
                                  q1 q2 (* 100 regression))}]))))

(defn- extract-high-iteration-signals
  "Extract signals from phases requiring many inner loop iterations."
  [phase-metrics threshold]
  (->> phase-metrics
       (filter #(and (:iterations %) (> (:iterations %) threshold)))
       (group-by :phase)
       (map (fn [[phase entries]]
              {:signal/type :high-iteration-count
               :signal/severity :medium
               :signal/evidence {:phase phase
                                 :avg-iterations (/ (reduce + (map :iterations entries))
                                                    (count entries))
                                 :count (count entries)}
               :signal/affected-heuristic (keyword (str "agent-prompt/" (name phase)))
               :signal/message (format "Phase %s averaging %.1f iterations (%d occurrences)"
                                       (name phase)
                                       (double (/ (reduce + (map :iterations entries))
                                                  (count entries)))
                                       (count entries))}))
       vec))

(defn extract-signals
  "Extract improvement signals from all available data sources.

   Arguments:
     data - map with optional keys:
       :slo-checks       - vector of SLO check results from reliability engine
       :failure-events    - vector of failure events with :failure/class
       :training-examples - vector of training records
       :phase-metrics     - vector of phase metric records
     config - optional {:min-failure-count 3 :min-examples 10 :iteration-threshold 3}

   Returns: vector of signal maps, sorted by severity (critical first)."
  [data & [config]]
  (let [{:keys [slo-checks failure-events training-examples phase-metrics]} data
        {:keys [min-failure-count min-examples iteration-threshold]
         :or {min-failure-count 3 min-examples 10 iteration-threshold 3}} config
        severity-order {:critical 0 :high 1 :medium 2 :low 3}]
    (->> (concat
          (extract-slo-breach-signals (or slo-checks []))
          (extract-failure-pattern-signals (or failure-events []) min-failure-count)
          (extract-quality-regression-signals (or training-examples []) min-examples)
          (extract-high-iteration-signals (or phase-metrics []) iteration-threshold))
         (sort-by #(get severity-order (:signal/severity %) 99))
         vec)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (extract-signals
   {:slo-checks [{:breached? true :sli/name :SLI-1 :slo/target 0.85
                   :slo/actual 0.70 :slo/tier :standard}]
    :failure-events (repeat 5 {:failure/class :failure.class/timeout
                                :timestamp (java.util.Date.)})})

  :leave-this-here)
