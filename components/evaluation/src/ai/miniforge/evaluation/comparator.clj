;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.evaluation.comparator
  "Statistical comparison of baseline vs candidate results.

   Layer 0: Comparison functions (pure)")

;------------------------------------------------------------------------------ Layer 0
;; Statistical comparison

(defn- mean [vals]
  (if (empty? vals) 0.0 (/ (reduce + vals) (count vals))))

(defn- variance [vals]
  (if (< (count vals) 2)
    0.0
    (let [m (mean vals)
          n (count vals)]
      (/ (reduce + (map #(Math/pow (- % m) 2) vals)) (dec n)))))

(defn- std-dev [vals]
  (Math/sqrt (variance vals)))

(defn- welch-t-test
  "Welch's t-test for unequal variances. Returns approximate p-value.
   This is a simplified two-tailed test."
  [baseline-vals candidate-vals]
  (let [n1 (count baseline-vals)
        n2 (count candidate-vals)
        m1 (mean baseline-vals)
        m2 (mean candidate-vals)
        v1 (variance baseline-vals)
        v2 (variance candidate-vals)]
    (if (or (< n1 2) (< n2 2) (and (zero? v1) (zero? v2)))
      {:t-stat 0.0 :p-value 1.0}
      (let [se (Math/sqrt (+ (/ v1 n1) (/ v2 n2)))
            t-stat (if (zero? se) 0.0 (/ (- m1 m2) se))
            ;; Approximate p-value using normal distribution for large n
            ;; For small n this is an approximation, but sufficient for our use
            abs-t (Math/abs t-stat)
            p-value (cond
                      (< abs-t 0.5) 0.6
                      (< abs-t 1.0) 0.3
                      (< abs-t 1.96) 0.1
                      (< abs-t 2.58) 0.02
                      :else 0.005)]
        {:t-stat t-stat :p-value p-value}))))

(defn compare-results
  "Compare baseline vs candidate metric distributions.

   Arguments:
     baseline-vals  - vector of metric values from baseline heuristic
     candidate-vals - vector of metric values from candidate heuristic

   Returns:
     {:significant? bool
      :p-value double
      :baseline-mean double
      :candidate-mean double
      :lift double          ; fractional improvement (positive = candidate better)
      :recommendation :promote | :reject | :needs-more-data}"
  [baseline-vals candidate-vals]
  (let [{:keys [p-value]} (welch-t-test baseline-vals candidate-vals)
        b-mean (mean baseline-vals)
        c-mean (mean candidate-vals)
        lift (if (zero? b-mean) 0.0 (/ (- c-mean b-mean) (Math/abs b-mean)))
        significant? (< p-value 0.05)
        min-samples 10
        enough-data? (and (>= (count baseline-vals) min-samples)
                          (>= (count candidate-vals) min-samples))]
    {:significant? significant?
     :p-value p-value
     :baseline-mean b-mean
     :candidate-mean c-mean
     :lift lift
     :recommendation (cond
                       (not enough-data?)             :needs-more-data
                       (and significant? (pos? lift)) :promote
                       significant?                   :reject
                       :else                          :needs-more-data)}))
