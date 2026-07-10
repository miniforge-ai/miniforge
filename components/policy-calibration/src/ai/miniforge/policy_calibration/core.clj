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

(defn- strict-majority?
  "True when `part` is more than half of `whole` — an outright majority, not a
   tie. The shared predicate for both consensus levels (trials within a fixture,
   passing runs among the evaluated). A tie is NOT a majority."
  [part whole]
  (> part (/ whole 2.0)))

(defn- majority-fired?
  "Over the OK trials of one fixture, did the rule fire in a strict majority?
   Trials are the judge's repeated votes on the SAME cell; the consensus vote —
   not any single stray call — is the fixture's verdict. This denoises transient
   judge FP/FN, and, because the judge is batched (one call scores every rule on
   a file), suppresses a single haywire call that would otherwise flip several
   rules at once. Ties (an even OK-trial count split evenly) resolve to NOT
   fired: a rule must win an outright majority to be charged a false positive or
   credited a catch, so ambiguous 50/50 evidence never gates and never inflates
   recall."
  [ok-count fired-count]
  (strict-majority? fired-count ok-count))

(defn score-rule
  "Score one rule against a single run's cells. `cell-at` is
   (fn [fixture-rel trial] -> cell). Each fixture's verdict is the MAJORITY vote
   across its OK trials (see `majority-fired?`) — a single stray trial does not
   decide the cell. Returns
   {:clean-fp :recall :evaluated? :failed :gate-ready? :fp-cases :fn-cases},
   where :clean-fp and recall count FIXTURES (by consensus verdict), not raw
   (fixture, trial) cells."
  [rule fixtures trials gate-bar cell-at]
  (let [rid       (:rule/id rule)
        seeded?   (fn [f] (contains? (:seeded f) rid))
        clean-fx  (remove seeded? fixtures)
        viol-fx   (filter seeded? fixtures)
        ok?       (fn [f t] (cell-ok? (cell-at (:rel f) t)))
        fired?    (fn [f t] (contains? (cell-fired (cell-at (:rel f) t)) rid))
        why       (fn [f t] (get (cell-why (cell-at (:rel f) t)) rid))
        ;; per-fixture consensus verdict across the OK trials
        verdict   (fn [f]
                    ;; realize once (filterv) — oks/fires are each traversed
                    ;; several times (count/some), and each pass re-runs the
                    ;; cell lookup
                    (let [oks   (filterv #(ok? f %) (range trials))
                          fires (filterv #(fired? f %) oks)]
                      {:evaluated? (boolean (seq oks))
                       :fired?     (majority-fired? (count oks) (count fires))
                       :why        (some #(why f %) fires)}))
        clean-v   (mapv (fn [f] [f (verdict f)]) clean-fx)
        viol-v    (mapv (fn [f] [f (verdict f)]) viol-fx)
        clean-ev  (filterv (fn [[_ v]] (:evaluated? v)) clean-v)
        viol-ev   (filterv (fn [[_ v]] (:evaluated? v)) viol-v)
        fp-cases  (vec (for [[f v] clean-ev :when (:fired? v)]
                         {:fixture (:rel f) :judge-said (:why v)}))
        fn-cases  (vec (for [[f v] viol-ev :when (not (:fired? v))]
                         {:fixture (:rel f)}))
        n-ev      (count viol-ev)
        recall    (if (pos? n-ev) (/ (double (- n-ev (count fn-cases))) n-ev) 1.0)
        clean-fp  (count fp-cases)
        failed    (count (for [f fixtures t (range trials) :when (not (ok? f t))] 1))
        evaluated?  (and (or (empty? clean-fx) (seq clean-ev))
                         (or (empty? viol-fx) (pos? n-ev)))
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
  "Combine a rule's per-run scores into a verdict. CONSENSUS: gate-ready if a
   strict MAJORITY of the EVALUATED runs pass. Each run is already a trial-level
   majority vote (see `majority-fired?`), so a run only fails on evidence
   stronger than a single stray judge call; run-level majority then tolerates
   one anomalous run without letting a rule that fails most runs gate.

   A run that produced NO successfully-judged cells (a rate-limited / backend-out
   window) is not evidence either way — it is EXCLUDED from the consensus, not
   counted as a failure. Its default recall=1.0 / fp=0 would otherwise
   masquerade as a clean pass. Certification still needs a strict majority of the
   CONFIGURED runs to have evaluated; below that the record is `:inconclusive?`
   and never gate-ready, so a run of crashes cannot certify by attrition.
   `:stable?` reports unanimity among evaluated runs — a gate-ready-but-unstable
   rule passed on majority, not consensus, and stays visible for audit."
  [per-run runs trials]
  (let [evaluated    (filterv :evaluated? per-run)
        n-eval       (count evaluated)
        enough-eval? (strict-majority? n-eval runs)
        pass-runs    (count (filter :gate-ready? evaluated))
        majority?    (and enough-eval? (strict-majority? pass-runs n-eval))
        all-pass?    (and (pos? n-eval) (= pass-runs n-eval))
        ;; recall / fp summarized over EVALUATED runs only — a crashed run's
        ;; default recall=1.0 / fp=0 is not evidence
        recalls      (map :recall evaluated)
        fps          (map :clean-fp evaluated)
        recall-min   (if (seq recalls) (round3 (apply min recalls)) 0.0)
        recall-max   (if (seq recalls) (round3 (apply max recalls)) 0.0)
        clean-fp-max (if (seq fps) (apply max fps) 0)
        ;; total non-:ok (errored / unparseable) judge cells across all runs —
        ;; a count of cells, not of runs
        failed-cells (reduce + (map :failed per-run))
        fp-cases     (vec (distinct (mapcat :fp-cases per-run)))
        fn-cases     (vec (distinct (mapcat :fn-cases per-run)))]
    {:gate-ready?     majority?
     ;; unanimity among the EVALUATED runs — needs at least one, else an
     ;; all-crashed (inconclusive) record would read as "stable"
     :stable?         (and (pos? n-eval) (or all-pass? (zero? pass-runs)))
     :runs            runs
     :evaluated-runs  n-eval
     :pass-runs       pass-runs
     :inconclusive?   (not enough-eval?)
     :trials          trials
     :run-verdicts    (mapv :gate-ready? per-run)
     :recall-min      recall-min
     :recall-max      recall-max
     :clean-fp-max    clean-fp-max
     :evaluated?      (pos? n-eval)
     :failed-cells    failed-cells
     :fp-cases        fp-cases
     :fn-cases        fn-cases}))

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
