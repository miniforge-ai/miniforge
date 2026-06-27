;; Gate-readiness calibration for policy-pack rules.
;;
;; A semantic rule earns `hard-halt` status only by passing calibration: the
;; LLM judge must NOT fire on clean fixtures (zero false positives) and must
;; fire on violating fixtures (recall >= bar), measured over K trials. This
;; emits a per-rule record (calibration.edn) the gate-readiness build check
;; reads to decide whether a hard-halt rule has earned the gate — and, for
;; rules that fail, the specific over-fire / miss cases (with the judge's
;; reasoning) so the rule's criteria can be sharpened and re-run.
;;
;; Run: clojure -M:dev:test -i eval/policy-fidelity/calibrate.clj
;; NOT on a source path.

(require '[ai.miniforge.phase.agent-behavior :as ab]
         '[ai.miniforge.llm.interface :as llm]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

;; ---- config / bar ----
(def trials 3)
(def max-parallel 5)
(def model {:label "opus-4.8" :backend :claude :model "claude-opus-4-8"})
(def gate-bar
  "A rule is gate-ready iff it never false-fires on a clean fixture across the
   trials (clean-fp = 0) AND catches at least `recall` of its seeded violations.
   Zero clean-FP is the load-bearing bar: a hard gate that blocks correct work
   is worse than one that occasionally misses (deterministic detectors + the
   reviewer layer backstop misses; nothing backstops a false block)."
  {:max-clean-fp 0 :min-recall 0.80})

(def fixture-root "eval/policy-fidelity/fixtures")
(def truth (edn/read-string (slurp (io/file fixture-root "truth.edn"))))

(defn ->rule-kw [x]
  (cond (keyword? x) x
        (string? x)  (keyword (str/replace x #"^:" ""))
        :else        nil))

(defn load-candidate-rules []
  (let [by-id (into {} (map (juxt :rule/id identity))
                    (into (ab/load-builtin-rules) (ab/load-standards-rules)))
        ids   (:candidate-rules truth)
        missing (remove by-id ids)]
    (when (seq missing)
      (throw (ex-info (str "candidate rules missing from packs: " (vec missing))
                      {:missing (vec missing)})))
    (mapv by-id ids)))

(defn bounded-pmap [n f coll]
  (vec (mapcat (fn [batch] (mapv deref (mapv #(future (f %)) batch)))
               (partition-all n coll))))

(defn parse-edn-vec [s]
  (try
    (let [s (-> (or s "") (str/replace #"(?s)```(?:edn|clojure)?" "") str/trim)
          v (edn/read-string s)]
      (if (vector? v) v ::parse-fail))
    (catch Exception _
      (try (if-let [m (re-find #"(?s)\[.*\]" (or s ""))] (edn/read-string m) ::parse-fail)
           (catch Exception _ ::parse-fail)))))

(def batched-system
  (str "You are a code reviewer enforcing engineering standards. Analyze ONE source "
       "file against a LIST of rules (each rendered as a `### <rule-id>` heading). Return ONLY a valid EDN vector of "
       "violation maps, no prose/markdown. Each map: {:rule-id <keyword> :line <int> "
       ":current <snippet> :message <why>}. Return [] if the file complies with all rules."))
(defn batched-user [rules path content]
  (str "## File: " path "\n\n```\n" content "\n```\n\n## Rules\n\n"
       (str/join "\n" (map (fn [r] (str "### " (:rule/id r) " — " (:rule/title r) "\n"
                                        (:rule/description r) "\n" (:rule/knowledge-content r))) rules))
       "\n\nReturn an EDN vector of violations across ALL rules (tag each :rule-id), or []."))

(defn judge [client rules path content]
  (let [r (llm/complete client {:system batched-system
                                :messages [{:role "user" :content (batched-user rules path content)}]
                                :model (:model model)})]
    (if-not (:success r)
      {:status :error}
      (let [v (parse-edn-vec (:content r))]
        (if (= ::parse-fail v)
          {:status :parse-fail}
          (let [viols (filter map? v)]
            {:status :ok
             :fired (set (keep (comp ->rule-kw :rule-id) viols))
             ;; rule-id -> judge message, for iteration feedback
             :why (into {} (keep (fn [m] (when-let [k (->rule-kw (:rule-id m))]
                                           [k (:message m)])) viols))}))))))

;; ---- run the judge over every fixture, K trials ----
(let [rules (load-candidate-rules)
      files (vec (for [[rel m] (:fixtures truth)]
                   {:rel rel
                    :content (slurp (io/file fixture-root (str rel ".txt")))
                    :seeded (:violates m)}))
      client (llm/create-client {:backend (:backend model)})
      jobs (for [f files, t (range trials)] {:f f :t t})
      _ (println (format "Calibrating %d rules over %d fixtures x %d trials (%s)...\n"
                         (count rules) (count files) trials (:label model)))
      results (bounded-pmap max-parallel
                            (fn [{:keys [f t]}]
                              (assoc (judge client rules (:rel f) (:content f)) :f f :t t))
                            jobs)
      ;; ---- per-rule scoring ----
      rule-record
      (fn [rule]
        (let [rid (:rule/id rule)
              ;; a fixture is "clean" for this rule when truth says it does NOT violate it
              clean-fx (remove #(contains? (:seeded %) rid) files)
              viol-fx  (filter #(contains? (:seeded %) rid) files)
              fired?   (fn [f t] (let [r (first (filter #(and (= (:rel f) (:rel (:f %))) (= t (:t %))) results))]
                                   (and (= :ok (:status r)) (contains? (:fired r) rid))))
              why      (fn [f t] (let [r (first (filter #(and (= (:rel f) (:rel (:f %))) (= t (:t %))) results))]
                                   (get (:why r) rid)))
              ;; false positives: clean fixture, rule fired (any trial)
              fp-cases (vec (for [f clean-fx t (range trials) :when (fired? f t)]
                              {:fixture (:rel f) :trial t :judge-said (why f t)}))
              ;; misses: violating fixture, rule did not fire (any trial)
              fn-cases (vec (for [f viol-fx t (range trials) :when (not (fired? f t))]
                              {:fixture (:rel f) :trial t}))
              n-viol-cells (* (count viol-fx) trials)
              recall   (if (pos? n-viol-cells)
                         (/ (double (- n-viol-cells (count fn-cases))) n-viol-cells)
                         1.0)
              clean-fp (count fp-cases)
              gate-ready? (and (<= clean-fp (:max-clean-fp gate-bar))
                               (>= recall (:min-recall gate-bar)))]
          [rid {:recall      (Double/parseDouble (format "%.3f" recall))
                :clean-fp    clean-fp
                :gate-ready? gate-ready?
                :fp-cases    fp-cases
                :fn-cases    fn-cases}]))
      record (into (sorted-map) (map rule-record rules))]
  ;; ---- report ----
  (println (format "%-34s %-11s %-9s %s" "rule" "gate-ready?" "clean-fp" "recall"))
  (doseq [[rid m] record]
    (println (format "%-34s %-11s %-9d %.2f%s"
                     (str rid) (str (:gate-ready? m)) (:clean-fp m) (:recall m)
                     (if (:gate-ready? m) "" "   <-- NOT gate-ready"))))
  (println)
  (doseq [[rid m] record :when (not (:gate-ready? m))]
    (println (format "%s — why not gate-ready:" rid))
    (doseq [c (:fp-cases m)]
      (println (format "  FALSE-FIRE on clean %s (trial %d): %s"
                       (:fixture c) (:trial c) (or (:judge-said c) "<no message>"))))
    (when (< (:recall m) (:min-recall gate-bar))
      (doseq [c (:fn-cases m)]
        (println (format "  MISSED violation in %s (trial %d)" (:fixture c) (:trial c)))))
    (println))
  (io/make-parents (io/file "eval/policy-fidelity/calibration.edn"))
  (spit "eval/policy-fidelity/calibration.edn"
        (with-out-str
          (pp/pprint {:generated-by "eval/policy-fidelity/calibrate.clj"
                      :model (:model model)
                      :trials trials
                      :gate-bar gate-bar
                      :rules record})))
  (println "calibration record ->" "eval/policy-fidelity/calibration.edn"))
(System/exit 0)
