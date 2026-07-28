;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
(ns ai.miniforge.diagnosis.signal
  "Signal extraction from observer metrics, reliability state, and training data.

   Signals are the raw inputs to the diagnosis engine. Each signal represents
   a detectable anomaly or pattern that may warrant adaptation.

   Layer 0: Constants
   Layer 1: Signal extraction (pure functions)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.diagnosis.messages :as msg]
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

;; Constants
(def ^{:stratum 0} ^:private config
  (-> (io/resource "config/diagnosis/defaults.edn") slurp edn/read-string))

;; Signal extraction
(defn- ^{:stratum 0} breach-to-signal
  "Convert a single SLO breach check into a signal map."
  [{:keys [sli/name slo/target slo/actual slo/tier]}]
  {:signal/type :slo-breach
   :signal/severity (if (= :critical tier) :critical :high)
   :signal/evidence {:sli-name name
                     :target target
                     :actual actual
                     :tier tier}
   :signal/message (msg/t :signal/slo-breach
                          {:name (clojure.core/name name)
                           :actual (format "%.2f" (double actual))
                           :target (format "%.2f" (double target))
                           :tier (clojure.core/name tier)})})

(defn- ^{:stratum 0} failure-to-signal
  "Convert a failure class frequency pair into a signal map."
  [min-count [cls cnt]]
  {:signal/type :recurring-failure
   :signal/severity (if (>= cnt (* 2 min-count)) :high :medium)
   :signal/evidence {:failure-class cls :count cnt}
   :signal/message (msg/t :signal/recurring-failure
                          {:class (clojure.core/name cls)
                           :count (str cnt)})})

(defn- ^{:stratum 0} meets-failure-threshold?
  "Returns true if the failure count meets the minimum threshold."
  [min-count [_ cnt]]
  (>= cnt min-count))

(defn- ^{:stratum 0} avg-quality
  "Compute the average quality score from a sequence of training examples."
  [examples]
  (let [scores (map #(get-in % [:training/labels :quality-score]) examples)
        valid (filter some? scores)]
    (if (seq valid)
      (/ (reduce + valid) (count valid))
      0.0)))

(defn- ^{:stratum 0} exceeds-iteration-threshold?
  "Returns true if the phase metric has iterations exceeding the threshold."
  [threshold metric]
  (and (:iterations metric) (> (:iterations metric) threshold)))

(defn- ^{:stratum 0} phase-to-signal
  "Convert a grouped phase entry into a high-iteration signal."
  [[phase entries]]
  (let [avg (double (/ (reduce + (map :iterations entries))
                       (count entries)))]
    {:signal/type :high-iteration-count
     :signal/severity :medium
     :signal/evidence {:phase phase
                       :avg-iterations avg
                       :count (count entries)}
     :signal/affected-heuristic (keyword (str "agent-prompt/" (name phase)))
     :signal/message (msg/t :signal/high-iteration
                            {:phase (name phase)
                             :avg (format "%.1f" avg)
                             :count (str (count entries))})}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} extract-slo-breach-signals
  "Extract signals from SLO breaches."
  [slo-checks]
  (->> slo-checks
       (filter :breached?)
       (mapv breach-to-signal)))

(defn- ^{:stratum 1} extract-failure-pattern-signals
  "Extract signals from recurring failure patterns."
  [failure-events min-count]
  (let [by-class (->> failure-events
                      (map :failure/class)
                      (filter some?)
                      frequencies)]
    (->> by-class
         (filter #(meets-failure-threshold? min-count %))
         (mapv #(failure-to-signal min-count %)))))

(defn- ^{:stratum 1} extract-quality-regression-signals
  "Extract signals from declining training example quality."
  [training-examples min-examples]
  (when (>= (count training-examples) (* 2 min-examples))
    (let [sorted (sort-by :training/timestamp training-examples)
          half (quot (count sorted) 2)
          first-half (take half sorted)
          second-half (drop half sorted)
          q1 (avg-quality first-half)
          q2 (avg-quality second-half)
          regression (- q1 q2)]
      (when (> regression (:quality-regression-threshold config))
        [{:signal/type :quality-regression
          :signal/severity (if (> regression (* 2 (:quality-regression-threshold config))) :high :medium)
          :signal/evidence {:first-half-quality q1
                            :second-half-quality q2
                            :regression regression}
          :signal/message (msg/t :signal/quality-regression
                                {:q1 (format "%.2f" (double q1))
                                 :q2 (format "%.2f" (double q2))
                                 :pct (format "%.0f" (* 100 regression))})}]))))

(defn- ^{:stratum 1} extract-high-iteration-signals
  "Extract signals from phases requiring many inner loop iterations."
  [phase-metrics threshold]
  (->> phase-metrics
       (filter #(exceeds-iteration-threshold? threshold %))
       (group-by :phase)
       (mapv phase-to-signal)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} extract-signals
  "Extract improvement signals from all available data sources.

   Arguments:
     data - map with optional keys:
       :slo-checks       - vector of SLO check results from reliability engine
       :failure-events    - vector of failure events with :failure/class
       :training-examples - vector of training records
       :phase-metrics     - vector of phase metric records
     config - optional {:min-failure-count 3 :min-examples 10 :iteration-threshold 3}

   Returns: vector of signal maps, sorted by severity (critical first)."
  [data & [overrides]]
  (let [{:keys [slo-checks failure-events training-examples phase-metrics]} data
        merged (merge config overrides)
        min-failure-count  (get merged :min-failure-count 3)
        min-examples       (get merged :min-training-examples 10)
        iteration-threshold (get merged :iteration-threshold 3)]
    (let [signals (concat
                   (extract-slo-breach-signals (or slo-checks []))
                   (extract-failure-pattern-signals (or failure-events []) min-failure-count)
                   (extract-quality-regression-signals (or training-examples []) min-examples)
                   (extract-high-iteration-signals (or phase-metrics []) iteration-threshold))
          sorted (shared/sort-by-severity :signal/severity signals)]
      (when-not (vector? sorted)
        ;; Extractors only emit canonical severities; an unknown value here
        ;; is a programmer error, so throwing matches the anomaly contract.
        (throw (ex-info "Unknown signal severity" {:anomaly sorted})))
      sorted)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (extract-signals
   {:slo-checks [{:breached? true :sli/name :SLI-1 :slo/target 0.85
                   :slo/actual 0.70 :slo/tier :standard}]
    :failure-events (repeat 5 {:failure/class :failure.class/timeout
                                :timestamp (java.util.Date.)})})

  :leave-this-here)
