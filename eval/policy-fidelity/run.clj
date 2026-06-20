;; Policy-judge fidelity eval — hardened harness.
;; Run: clojure -M:dev:test -i eval/policy-fidelity/run.clj
;; Edit `models` / `trials` below to widen the matrix. Seeded ground truth in
;; fixtures/truth.edn; writes a per-violation audit log to results/latest.edn.
;; NOT on a source path.

(require '[ai.miniforge.phase.agent-behavior :as ab]
         '[ai.miniforge.semantic-analyzer.interface :as sem]
         '[ai.miniforge.llm.interface :as llm]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.pprint :as pp]
         '[cheshire.core :as json])

;; The codex (GPT) backend returns raw codex JSONL as :content — the answer is
;; in an item.completed/agent_message/text field, not clean text like the claude
;; backend. Extract it so GPT is judged on its actual output, not the wrapper.
(defn normalize-content [s]
  (if (and (string? s) (str/includes? s "\"item.completed\""))
    (->> (str/split-lines s)
         (keep (fn [line] (try (let [m (json/parse-string line true)]
                                 (when (= "agent_message" (get-in m [:item :type]))
                                   (get-in m [:item :text])))
                               (catch Exception _ nil))))
         (str/join "\n"))
    s))

;; ---- config ----
(def trials 3)
(def max-parallel 5)
(def models [{:label "opus-4.8" :backend :claude :model "claude-opus-4-8"}])
;; which strategies to run this pass — A-vs-B is established; #{:B} validates the
;; locked config's fidelity at scale without paying for A's per-rule call fan-out.
(def strategies #{:B})

(def fixture-root "eval/policy-fidelity/fixtures")
(def truth (edn/read-string (slurp (io/file fixture-root "truth.edn"))))

(defn ->rule-kw
  "Coerce a model-emitted :rule-id to a keyword — accepts a keyword, a
   \":std/foo\" string, or a \"std/foo\" string. nil for anything else."
  [x]
  (cond (keyword? x) x
        (string? x)  (keyword (str/replace x #"^:" ""))
        :else        nil))

(defn load-candidate-rules []
  (let [by-id (into {} (map (juxt :rule/id identity))
                    (into (ab/load-builtin-rules) (ab/load-standards-rules)))
        ids (:candidate-rules truth)
        missing (remove by-id ids)]
    (when (seq missing)
      (throw (ex-info (str "candidate rules missing from packs: " (vec missing))
                      {:missing (vec missing)})))
    (mapv by-id ids)))

(defn bounded-pmap [n f coll]
  (vec (mapcat (fn [batch] (mapv deref (mapv #(future (f %)) batch)))
               (partition-all n coll))))

;; ---- judge response parsing (tolerant) ----
(defn parse-edn-vec [s]
  (try
    (let [s (-> (or s "") (str/replace #"(?s)```(?:edn|clojure)?" "") str/trim)
          v (edn/read-string s)]
      (if (vector? v) v ::parse-fail))
    (catch Exception _
      (try (if-let [m (re-find #"(?s)\[.*\]" (or s ""))] (edn/read-string m) ::parse-fail)
           (catch Exception _ ::parse-fail)))))

;; ---- strategy B batched prompt ----
(def batched-system
  (str "You are a code reviewer enforcing engineering standards. Analyze ONE source "
       "file against a NUMBERED LIST of rules. Return ONLY a valid EDN vector of "
       "violation maps, no prose/markdown. Each map: {:rule-id <keyword> :line <int> "
       ":current <snippet> :message <why>}. Return [] if the file complies with all rules."))
(defn batched-user [rules path content]
  (str "## File: " path "\n\n```\n" content "\n```\n\n## Rules\n\n"
       (str/join "\n" (map (fn [r] (str "### " (:rule/id r) " — " (:rule/title r) "\n"
                                        (:rule/description r) "\n" (:rule/knowledge-content r))) rules))
       "\n\nReturn an EDN vector of violations across ALL rules (tag each :rule-id), or []."))

;; ---- judges ----
(defn judge-A [client model rule path content]
  (let [{:keys [system user]} (sem/build-judge-prompt rule path content)
        r (llm/complete client {:system system :messages [{:role "user" :content user}] :model model})
        v (parse-edn-vec (normalize-content (:content r)))
        viols (when (vector? v) (vec (filter map? v)))]
    (cond (= ::parse-fail v) {:flagged? :parse-fail :violations []}
          (seq viols)        {:flagged? true :violations viols}
          :else              {:flagged? false :violations []})))

(defn judge-B [client model rules path content]
  (let [r (llm/complete client {:system batched-system
                                :messages [{:role "user" :content (batched-user rules path content)}] :model model})
        v (parse-edn-vec (normalize-content (:content r)))]
    (if (= ::parse-fail v)
      {:flagged-set :parse-fail :violations []}
      {:flagged-set (set (keep (comp ->rule-kw :rule-id) (filter map? v)))
       :violations (vec (filter map? v))})))

;; ---- scoring ----
(defn confusion [rows]               ; rows: {:seeded? :flagged?(true/false/:parse-fail)}
  (let [hit? (fn [r] (true? (:flagged? r)))
        pf?  (fn [r] (= :parse-fail (:flagged? r)))]
    {:tp (count (filter #(and (:seeded? %) (hit? %)) rows))
     :fn (count (filter #(and (:seeded? %) (not (hit? %))) rows))
     :fp (count (filter #(and (not (:seeded? %)) (hit? %)) rows))
     :tn (count (filter #(and (not (:seeded? %)) (not (hit? %)) (not (pf? %))) rows))
     :parse-fail (count (filter pf? rows))}))

(defn rate [n d] (if (pos? d) (/ (double n) d) 1.0))
(defn r+p [{:keys [tp fn fp]}] {:recall (rate tp (+ tp fn)) :precision (rate tp (+ tp fp))})
(defn mean [xs] (if (seq xs) (/ (reduce + xs) (double (count xs))) 0.0))

(let [rules  (load-candidate-rules)
      rule-ids (mapv :rule/id rules)
      ;; truth keys are the realistic .clj path the judge sees; fixtures live on
      ;; disk as <key>.txt so clj-kondo's *.clj staged-lint skips them (they are
      ;; intentional rule-violations, not source).
      files  (vec (for [[rel m] (:fixtures truth)]
                    {:rel rel :content (slurp (io/file fixture-root (str rel ".txt"))) :seeded (:violates m)}))
      audit  (atom [])]
  (println (format "Fidelity eval — %d fixtures, %d candidate rules, %d trials\n" (count files) (count rules) trials))
  (doseq [{:keys [label backend model]} models]
    (let [client (llm/create-client {:backend backend})
          ;; A jobs: per (file, rule, trial)
          a-jobs (for [f files, r rules, t (range trials)] {:f f :r r :t t})
          a-out  (if (contains? strategies :A)
                   (bounded-pmap max-parallel
                                 (fn [{:keys [f r t]}]
                                   (let [{:keys [flagged? violations]} (judge-A client model r (:rel f) (:content f))]
                                     (swap! audit conj {:model label :strategy :A :file (:rel f) :rule (:rule/id r)
                                                        :trial t :flagged? flagged? :violations violations})
                                     {:file (:rel f) :rule (:rule/id r) :trial t
                                      :seeded? (contains? (:seeded f) (:rule/id r)) :flagged? flagged?}))
                                 a-jobs)
                   [])
          ;; B jobs: per (file, trial) -> expand to rules
          b-jobs (for [f files, t (range trials)] {:f f :t t})
          b-raw  (if (contains? strategies :B)
                   (bounded-pmap max-parallel
                                 (fn [{:keys [f t]}]
                                   (let [{:keys [flagged-set violations]} (judge-B client model rules (:rel f) (:content f))]
                                     (swap! audit conj {:model label :strategy :B :file (:rel f) :trial t
                                                        :flagged-set flagged-set :violations violations})
                                     {:f f :t t :flagged-set flagged-set}))
                                 b-jobs)
                   [])
          b-out  (for [{:keys [f t flagged-set]} b-raw, r rules]
                   {:file (:rel f) :rule (:rule/id r) :trial t
                    :seeded? (contains? (:seeded f) (:rule/id r))
                    :flagged? (cond (= :parse-fail flagged-set) :parse-fail
                                    (contains? flagged-set (:rule/id r)) true :else false)})
          ;; per-trial scoring then mean
          per-trial (fn [rows] (for [t (range trials)] (r+p (confusion (filter #(= t (:trial %)) rows)))))
          flaky (fn [rows] (->> (group-by (juxt :file :rule) rows)
                                (filter (fn [[_ rs]] (> (count (set (map :flagged? rs))) 1)))
                                count))
          summ (fn [rows] (let [pt (per-trial rows)]
                            {:recall (mean (map :recall pt)) :precision (mean (map :precision pt))
                             :recall-range [(apply min (map :recall pt)) (apply max (map :recall pt))]
                             :confusion-allTrials (confusion rows) :flaky-cells (flaky rows)}))]
      (println "=== JUDGE:" label "===")
      (when (contains? strategies :A) (println "  A (per-rule): " (summ a-out)))
      (when (contains? strategies :B) (println "  B (batched):  " (summ b-out)))
      (println "  per-rule recall (mean over trials, seeded only):")
      (let [scored (if (seq a-out) a-out b-out)
            rc (fn [rows rid] (mean (for [t (range trials)]
                                      (let [s (filter #(and (= rid (:rule %)) (:seeded? %) (= t (:trial %))) rows)]
                                        (rate (count (filter #(true? (:flagged? %)) s)) (count s))))))]
        (doseq [rid rule-ids]
          (let [n (count (filter #(and (= rid (:rule %)) (:seeded? %) (= 0 (:trial %))) scored))]
            (when (pos? n)
              (println (format "    %-32s %s  (n=%d)" (str rid)
                               (str/join " | " (cond-> []
                                                 (contains? strategies :A) (conj (format "A %.2f" (rc a-out rid)))
                                                 (contains? strategies :B) (conj (format "B %.2f" (rc b-out rid)))))
                               n))))))
      (println)))
  (io/make-parents (io/file fixture-root "../results/latest.edn"))
  (spit (io/file fixture-root "../results/latest.edn") (with-out-str (pp/pprint @audit)))
  (println "audit log:" (str (count @audit) " judgments -> eval/policy-fidelity/results/latest.edn")))
(System/exit 0)
