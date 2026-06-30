(ns ai.miniforge.policy-calibration.core
  "Gate-readiness calibration: decide whether a semantic policy rule is reliable
   enough to be a hard gate by measuring the judge's false-positive and recall
   rates over a seeded corpus, across independent runs (consensus).

   The judge is INJECTED (`:judge-fn`), so this core has no LLM dependency and is
   unit-testable with mock cell results."
  (:require [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0
;; Judge cell — the outcome of one judge call on one fixture. A cell is EITHER a
;; success value (the rules it flagged + why) OR an anomaly:
;;   :unavailable    — the judge backend failed (LLM/CLI error)
;;   :invalid-input  — the judge's output was not parseable (a format error)

(defn cell-success
  "A successful judge cell: the set of rule-ids the judge flagged on the fixture,
   and a {rule-id message} map (the per-rule reasoning, for iteration feedback)."
  [fired why]
  {:calibration/fired (set fired) :calibration/why (into {} why)})

(defn backend-error
  "Cell for a judge backend failure (LLM/CLI error) — a downstream dependency
   was unavailable, not evidence the fixture is clean."
  [message data]
  (anomaly/anomaly :unavailable message data))

(defn format-error
  "Cell for unparseable judge output — the response was malformed (invalid
   format), not evidence the fixture is clean."
  [message data]
  (anomaly/anomaly :invalid-input message data))

(defn cell-ok?
  "True only for a success cell. A nil or any non-success value (a missing
   lookup, a misbehaving judge, an anomaly) is NOT ok — it must not be treated
   as a clean pass."
  [cell]
  (and (map? cell) (contains? cell :calibration/fired)))
(defn cell-fired [cell] (:calibration/fired cell))
(defn cell-why   [cell] (:calibration/why cell))

;------------------------------------------------------------------------------ Layer 1
;; Per-run scoring — only successfully-judged cells count toward FP and recall;
;; a backend/format-error cell is not evidence a rule is clean, and a rule with
;; no successful evaluation on its fixtures cannot be certified.

(defn score-rule
  "Score one rule against a single run's cells. `cell-at` is
   (fn [fixture-rel trial] -> cell). Returns
   {:clean-fp :recall :evaluated? :failed :gate-ready? :fp-cases :fn-cases}."
  [rule fixtures trials gate-bar cell-at]
  (let [rid      (:rule/id rule)
        seeded?  (fn [f] (contains? (:seeded f) rid))
        clean-fx (remove seeded? fixtures)
        viol-fx  (filter seeded? fixtures)
        ok?      (fn [f t] (cell-ok? (cell-at (:rel f) t)))
        fired?   (fn [f t] (contains? (cell-fired (cell-at (:rel f) t)) rid))
        why      (fn [f t] (get (cell-why (cell-at (:rel f) t)) rid))
        fp-cases (vec (for [f clean-fx t (range trials) :when (and (ok? f t) (fired? f t))]
                        {:fixture (:rel f) :judge-said (why f t)}))
        viol-ok  (vec (for [f viol-fx t (range trials) :when (ok? f t)] [f t]))
        fn-cases (vec (for [[f t] viol-ok :when (not (fired? f t))] {:fixture (:rel f)}))
        n-ok     (count viol-ok)
        recall   (if (pos? n-ok) (/ (double (- n-ok (count fn-cases))) n-ok) 1.0)
        clean-fp (count fp-cases)
        clean-ok (count (for [f clean-fx t (range trials) :when (ok? f t)] 1))
        failed   (count (for [f fixtures t (range trials) :when (not (ok? f t))] 1))
        evaluated?  (and (or (empty? clean-fx) (pos? clean-ok))
                         (or (empty? viol-fx) (pos? n-ok)))
        gate-ready? (and evaluated?
                         (<= clean-fp (:max-clean-fp gate-bar))
                         (>= recall (:min-recall gate-bar)))]
    {:clean-fp    clean-fp
     :recall      recall
     :evaluated?  evaluated?
     :failed      failed
     :gate-ready? gate-ready?
     :fp-cases    fp-cases
     :fn-cases    fn-cases}))

(def ^:private recall-precision-fmt
  "Format for rounding recall to 3 decimal places — enough granularity to
   distinguish stability tiers without overstating judge precision."
  "%.3f")

(defn- round3 [x] (Double/parseDouble (format recall-precision-fmt x)))

(defn aggregate
  "Combine a rule's per-run scores into a stable verdict. CONSENSUS: gate-ready
   only if it passes EVERY run; a verdict that flips across runs is unstable and
   therefore not ready (a hard gate must be reliably ready, not on-average)."
  [per-run runs trials]
  (let [flags        (mapv :gate-ready? per-run)
        all-pass?    (every? true? flags)
        recall-min   (round3 (apply min (map :recall per-run)))
        recall-max   (round3 (apply max (map :recall per-run)))
        clean-fp-max (apply max (map :clean-fp per-run))
        ;; total non-:ok (errored / unparseable) judge cells across all runs —
        ;; a count of cells, not of runs
        failed-cells (reduce + (map :failed per-run))
        fp-cases     (vec (distinct (mapcat :fp-cases per-run)))
        fn-cases     (vec (distinct (mapcat :fn-cases per-run)))]
    {:gate-ready?  all-pass?
     :stable?      (or all-pass? (every? false? flags))
     :runs         runs
     :trials       trials
     :run-verdicts flags
     :recall-min   recall-min
     :recall-max   recall-max
     :clean-fp-max clean-fp-max
     :evaluated?   (every? :evaluated? per-run)
     :failed-cells failed-cells
     :fp-cases     fp-cases
     :fn-cases     fn-cases}))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration — `judge-fn` is injected: (fn [rules fixture] -> cell).

(defn- bounded-pmap [n f coll]
  (vec (mapcat (fn [batch] (mapv deref (mapv #(future (f %)) batch)))
               (partition-all n coll))))

(def ^:private judge-cell-max-attempts
  "Judge calls per (fixture, trial) before a non-OK result is recorded as a
   failed cell. A single transient backend/format hiccup in a large run
   (~100+ calls) would otherwise scar the whole record — and, because the
   judge is batched, that one failed call counts against every rule in the
   batch. A small bounded retry lets an intermittent failure self-heal while a
   persistent one still records as data (the error cell is excluded from
   scoring either way, so this only cleans the evidence, never the verdict)."
  3)

(defn- judge-once
  "One judge call, turning any thrown error into a backend-error cell so a
   failure records as data rather than aborting the run on deref."
  [judge-fn rules fixture]
  (try (judge-fn rules fixture)
       (catch Throwable e
         (backend-error "judge threw" {:error (ex-message e)}))))

(defn- judge-cell
  "Run the injected judge for one (fixture, trial), retrying a non-OK cell up to
   `judge-cell-max-attempts` so an intermittent backend/format failure does not
   scar an otherwise-clean run. Returns the first OK cell, or the last error."
  [judge-fn rules fixture]
  (loop [attempt 1]
    (let [cell (judge-once judge-fn rules fixture)]
      (if (or (cell-ok? cell) (>= attempt judge-cell-max-attempts))
        cell
        (recur (inc attempt))))))

(defn- run-pass
  [{:keys [rules fixtures judge-fn trials max-parallel gate-bar]}]
  (let [jobs    (for [f fixtures t (range trials)] [f t])
        cells   (into {} (bounded-pmap max-parallel
                                       (fn [[f t]] [[(:rel f) t] (judge-cell judge-fn rules f)])
                                       jobs))
        cell-at (fn [rel t] (get cells [rel t]))]
    (into {} (map (fn [rule]
                    [(:rule/id rule) (score-rule rule fixtures trials gate-bar cell-at)]))
          rules)))

(defn calibrate
  "Run `:runs` independent passes over the corpus and return the aggregated
   per-rule gate-ready record (a sorted map of rule-id -> verdict).

   opts: {:rules [...] :fixtures [{:rel :content :seeded #{rule-ids}}]
          :judge-fn (fn [rules fixture] -> cell) :runs :trials :max-parallel
          :gate-bar {:max-clean-fp :min-recall}}"
  [{:keys [rules runs trials] :as opts}]
  (let [passes (mapv (fn [_] (run-pass opts)) (range runs))]
    (into (sorted-map)
          (map (fn [rule]
                 (let [rid (:rule/id rule)]
                   [rid (aggregate (mapv #(get % rid) passes) runs trials)])))
          rules)))

(comment
  ;; Pure scoring + consensus; judge injected (here a stub that fires the seeded set):
  (calibrate {:rules    [{:rule/id :r/x}]
              :fixtures [{:rel "a.clj" :seeded #{:r/x}}]
              :judge-fn (fn [_rules f] (cell-success (:seeded f) {}))
              :runs 2 :trials 1 :max-parallel 1
              :gate-bar {:max-clean-fp 0 :min-recall 0.8}})
  :leave-this-here)
