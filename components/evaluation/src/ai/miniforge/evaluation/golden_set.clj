;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.evaluation.golden-set
  "Golden set management per N1 §3.3.3.

   Golden sets are curated workflow inputs paired with known-good outcomes.
   They are stored as N6 evidence artifacts.

   Layer 0: Golden set CRUD (pure data)
   Layer 1: Golden set evaluation")

;------------------------------------------------------------------------------ Layer 0
;; Golden set management

(defn create-golden-set
  "Create a golden set from curated entries.

   Arguments:
     opts - {:id string :entries [...] :version string}
       Each entry: {:entry/id :entry/input :entry/expected-outcome
                    :entry/pass-criteria [...] :entry/source :entry/tags}

   Returns: golden set map"
  [{:keys [id entries version]}]
  {:golden-set/id (or id (str (random-uuid)))
   :golden-set/entries (or entries [])
   :golden-set/version (or version "1.0.0")
   :golden-set/created-at (java.util.Date.)})

(defn add-entry
  "Add an entry to a golden set. Returns updated golden set."
  [golden-set entry]
  (update golden-set :golden-set/entries conj entry))

(defn entry-count [golden-set]
  (count (:golden-set/entries golden-set)))

;------------------------------------------------------------------------------ Layer 1
;; Evaluation

(defn evaluate-entry
  "Evaluate a single golden set entry against actual output.

   Arguments:
     entry    - golden set entry with :entry/pass-criteria
     actual   - actual output from running the entry's input
     criteria-fn - function that checks (criterion actual) -> bool

   Returns: {:entry/id :passed? :criteria-results [...]}"
  [entry actual criteria-fn]
  (let [criteria    (:entry/pass-criteria entry [])
        check       (fn [criterion] {:criterion criterion
                                     :passed? (boolean (criteria-fn criterion actual))})
        results     (mapv check criteria)
        all-passed? (every? :passed? results)]
    {:entry/id (:entry/id entry)
     :passed? all-passed?
     :criteria-results results}))

(defn run-golden-set
  "Run a golden set and compare actual vs expected outcomes.

   Arguments:
     golden-set  - golden set map
     execute-fn  - (fn [input]) -> actual output
     criteria-fn - (fn [criterion actual]) -> boolean

   Returns: {:total :passed :failed :regressions :pass-rate :results [...]}"
  [golden-set execute-fn criteria-fn]
  (let [run-entry (fn [entry]
                    (evaluate-entry entry (execute-fn (:entry/input entry)) criteria-fn))
        results   (mapv run-entry (:golden-set/entries golden-set))
        passed    (count (filter :passed? results))
        failed    (count (remove :passed? results))
        total     (count results)]
    {:total total
     :passed passed
     :failed failed
     :pass-rate (if (zero? total) 0.0 (double (/ passed total)))
     :results results}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def gs (-> (create-golden-set {:id "test" :version "1.0.0"})
              (add-entry {:entry/id "e1" :entry/input {:x 1}
                           :entry/pass-criteria [:non-nil]})))
  (entry-count gs)
  (run-golden-set gs (fn [input] {:result (:x input)}) (fn [_ actual] (some? actual)))

  :leave-this-here)
