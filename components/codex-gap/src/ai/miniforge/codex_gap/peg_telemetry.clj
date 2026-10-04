;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.codex-gap.peg-telemetry
  "T1 SPEC §7.7 per-peg telemetry, computed from records that already
   exist: the gap ledger's :miss/pegs (which discriminators a
   consultation presented, each as {:id :answer nil :landings {answer
   [landing-ids]}}) and the run's
   gate-history.edn (every gate decision per iteration).

   A peg's recorded answer is the verdict of the mechanism its landing
   problem carries (§4.5 `mechanism` pointer): the mapped gate denied in
   an implement iteration -> the guarded failure occurred; allowed -> it
   did not. Pegs whose landings carry no mapped mechanism are reported
   :unobserved -- no answer is invented from prose.

   Two §4.4.1 trigger signatures fall out: answer entropy near zero over
   enough observations (the question stopped discriminating), and answer
   branches whose landing sets are identical (the board collapsed under
   the peg; the remedy is a branch merge, not retirement)."
  (:require [ai.miniforge.codex-gap.ledger :as ledger]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} mechanism-gate-map-resource
  "Classpath resource mapping mechanism pointers to gate keywords."
  "config/codex-gap/mechanism-gate-map.edn")

(def ^{:stratum 0} gate-history-filename
  "The workflow checkpoint's append-only gate decision record."
  "gate-history.edn")

(def ^{:stratum 0} min-observations
  "Observations a peg needs before its entropy can trigger review: below
   this, a flat distribution is small numbers, not a dead question."
  10)

(def ^{:stratum 0} entropy-floor-bits
  "Answer entropy (bits) at or below which a peg has stopped
   discriminating. 0.2 bits is roughly 97:3 on a binary answer."
  0.2)

(defn ^{:stratum 0} entropy-bits
  "Shannon entropy in bits of a {value count} map; 0 for empty."
  [freqs]
  (let [total (double (reduce + 0 (vals freqs)))]
    (if (zero? total)
      0.0
      (- (reduce + 0.0
                 (for [[_ n] freqs :when (pos? n)
                       :let [p (/ n total)]]
                   (* p (/ (Math/log p) (Math/log 2)))))))))

(defn ^{:stratum 0} peg-landings
  "The peg's {answer [landing-ids]} map. The ledger's per-peg record
   (built by phase-software-factory's consultation summary) carries it
   under :landings, with :answer nil; the raw codex basis carries it
   under :answers. Both are read so a record from either side counts."
  [peg]
  (or (get peg :landings) (get peg :answers) {}))

(defn ^{:stratum 0} gate-answers
  "The answers a run's gate-history records for `gate`: one per
   implement iteration, :denied when the gate is among that iteration's
   failures, :allowed otherwise. Non-implement entries are not answers."
  [history gate]
  (into []
        (comp (filter #(= :implement (:phase %)))
              (map (fn [entry]
                     (if (some #(= gate (:gate %)) (:phase/gate-failures entry))
                       :denied
                       :allowed))))
        history))

(defn- ^{:stratum 0} presented-pegs
  "Every distinct peg presented in a run, keyed by id, from the two
   per-run sources read once by the caller. Consultation rows (SPEC
   $7.7.2.1 -- the snapshot the run actually presented) own the landing
   metadata outright: a miss row never displaces one, whatever either
   row's answer state, because a stale miss snapshot picking the
   mechanism is exactly the defect this merge exists to prevent. Miss
   rows may only DONATE an answer the consultation rows lack (the
   lost-consultation-file survival path) or stand in whole for ids the
   consultation record never presented. Within one source, an answered
   row beats an answerless one for its id."
  [miss-pegs consult-pegs]
  (let [one-source (fn [acc {:keys [id] :as peg}]
                     (cond
                       (nil? id) acc
                       (some? (:answer peg)) (assoc acc id peg)
                       (contains? acc id) acc
                       :else (assoc acc id peg)))
        consults (reduce one-source {} consult-pegs)]
    (reduce (fn [acc {:keys [id] :as peg}]
              (cond
                (nil? id) acc
                (contains? acc id)
                (if (and (some? (:answer peg))
                         (nil? (:answer (get acc id))))
                  (update acc id assoc :answer (:answer peg))
                  acc)
                :else (one-source acc peg)))
            consults
            miss-pegs)))

(defn- ^{:stratum 0} explicit-answers-by-id
  "{peg-id [answer ..]} -- every non-nil explicit :answer across the
   run's consultation rows, in record order."
  [consult-pegs]
  (reduce (fn [acc {:keys [id answer]}]
            (if (and id (some? answer))
              (update acc id (fnil conj []) answer)
              acc))
          {}
          consult-pegs))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} branches-collapsed?
  "True when every answer of `peg` lands on the same problem set -- the
   second §4.4.1 signature. A peg with fewer than two answers cannot
   collapse."
  [peg]
  (let [landings (map set (vals (peg-landings peg)))]
    (and (> (count landings) 1)
         (apply = landings))))

(defn ^{:stratum 1} peg-mechanisms
  "Mechanism pointers carried by the problems `peg` can land on, from
   `nodes` ({id node}); sorted so the choice among several is stable
   across runs; empty when none carries one."
  [peg nodes]
  (->> (vals (peg-landings peg))
       (apply concat)
       (keep #(get-in nodes [% :mechanism]))
       distinct
       sort
       vec))

(defn ^{:stratum 1} load-mechanism-gate-map
  "The mechanism->gate map, or {} when the resource is absent."
  []
  (if-let [r (io/resource mechanism-gate-map-resource)]
    (edn/read-string (slurp r))
    {}))

(defn ^{:stratum 1} read-gate-history
  "A run's gate-history.edn as {:entries [..] :unreadable? bool
   :skipped n}. Torn nonblank lines are skipped AND counted (a torn
   last line must not lose the run, but missing verdicts must not pass
   as complete input either); an unreadable EXISTING file yields no
   entries with :unreadable? true, and a missing file is simply a run
   that never wrote one (one run's IO failure must not abort the whole
   scan)."
  [run-dir]
  (let [f (io/file run-dir gate-history-filename)]
    (if-not (.exists f)
      {:entries [] :unreadable? false :skipped 0}
      ;; Streamed line by line: the file is append-only and unbounded.
      ;; Plain try, IOException only (std 211 ex. a): this is the IO
      ;; boundary of a read-only report.
      (try
        (with-open [rdr (io/reader f)]
          (reduce (fn [acc line]
                    (if (str/blank? line)
                      acc
                      (let [v (try (edn/read-string {:default (fn [_ v] v)} line)
                                   (catch Exception _ ::torn))]
                        (if (= ::torn v)
                          (update acc :skipped inc)
                          (update acc :entries conj v)))))
                  {:entries [] :unreadable? false :skipped 0}
                  (line-seq rdr)))
        ;; An unreadable EXISTING file is incomplete input, distinct from
        ;; a run that never wrote one.
        (catch java.io.IOException _ {:entries [] :unreadable? true :skipped 0})))))

(defn ^{:stratum 1} aggregate
  "Fold per-run observations into the §7.7 record per peg. The entropy
   stream never mixes answer vocabularies (SPEC $7.7.2.2): when any run
   observed the peg through its mechanism, the counted distribution is
   the mechanism-sourced runs' alone -- a peg whose landings gained a
   mechanism mid-window must not have its gate verdicts diluted by
   earlier explicit answers (two constant single-source streams would
   otherwise fake a live distribution and suppress the trigger)."
  [observations]
  (into (sorted-map)
        (map (fn [[peg obs]]
               (let [mech-obs (filter #(= :mechanism (:answer-source %)) obs)
                     counted (if (seq mech-obs)
                               mech-obs
                               (filter #(= :explicit (:answer-source %)) obs))
                     answers (mapcat :answers counted)
                     freqs (frequencies answers)
                     n (count answers)
                     entropy (entropy-bits freqs)
                     collapsed (count (filter :collapsed? obs))]
                 [peg {:runs (count obs)
                       :unanswered-runs (count (remove (comp seq :answers) obs))
                       :answer-sources (frequencies (keep :answer-source obs))
                       :counted-source (cond (seq mech-obs) :mechanism
                                             (seq counted) :explicit
                                             :else nil)
                       ;; runs where gate verdicts outranked recorded valid
                       ;; explicit answers (SPEC $7.7.2.2) -- the
                       ;; disagreement reader's pointer, never counted
                       ;; into the entropy stream
                       :mechanism-overrode-explicit
                       (count (filter :explicit-answers obs))
                       ;; the overridden values themselves, so a
                       ;; disagreement is inspectable, not just countable
                       :explicit-answers (vec (mapcat :explicit-answers obs))
                       :invalid-answers (vec (mapcat :invalid-answers obs))
                       ;; The mechanism whose gate produced the counted
                       ;; stream names the record; an explicit-only run's
                       ;; unrelated mechanism must not label another
                       ;; gate's verdicts. Ties resolve by sorted name.
                       :mechanism (or (->> mech-obs (keep :mechanism) sort first)
                                      (->> obs (filter :observed?) (keep :mechanism) sort first)
                                      (->> obs (keep :mechanism) sort first))
                       :observed? (boolean (some :observed? obs))
                       :observations n
                       :answers freqs
                       :entropy-bits (/ (Math/round (* 1000 entropy)) 1000.0)
                       :collapsed-runs collapsed
                       :trigger (cond
                                  (pos? collapsed) :branches-collapsed
                                  (and (>= n min-observations) (<= entropy entropy-floor-bits)) :entropy
                                  :else nil)}])))
        (group-by :peg observations)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-observations
  "Observations for every distinct peg presented in `run-dir`
   (consultation record first, miss ledger as the pre-$7.7.2 fallback).
   Returns {:observations [..] :incomplete? bool} -- :incomplete? marks
   a run whose records are known-partial: a ledger/consultation read
   anomaly, torn rows skipped by the line reader, or an unreadable
   EXISTING gate-history file (a missing one is just a run that never
   wrote it). Such a run's surviving observations still count, but the
   report must name it rather than show a quieter run.

   Per observation, the counted :answers stream obeys SPEC $7.7.2.2 --
   mechanism outranks self-report, but only when the mechanism actually
   ANSWERED: gate-history writes are best-effort, and a mapped gate with
   no recorded verdicts must not discard a recorded explicit answer.
   Explicit answers are counted only when inside the peg's recorded
   vocabulary (the landing-map keys); out-of-vocabulary recordings ride
   :invalid-answers for the channel reader and never enter the stream.
   When gate verdicts win over recorded valid explicit answers, those
   ride :explicit-answers for the disagreement reader."
  [run-dir nodes gate-map]
  (let [ledger-res (ledger/read-ledger (str run-dir))
        consult-res (ledger/read-consultations (str run-dir))
        miss-pegs (mapcat :miss/pegs (get ledger-res :entries []))
        consult-pegs (mapcat :consultation/pegs (get consult-res :entries []))
        pegs (vals (presented-pegs miss-pegs consult-pegs))
        explicit-by-id (explicit-answers-by-id consult-pegs)
        history (read-gate-history run-dir)
        entries (:entries history)]
    {:incomplete? (boolean (or (:codex-gap/anomaly ledger-res)
                               (:codex-gap/anomaly consult-res)
                               (:unreadable? history)
                               (pos? (get history :skipped 0))
                               (pos? (get ledger-res :skipped 0))
                               (pos? (get consult-res :skipped 0))))
     :observations
     (vec
      (for [peg pegs
            :let [mechanisms (peg-mechanisms peg nodes)
                  ;; The mechanism reported is the one whose gate produced
                  ;; the answers -- the first mapped one, in sorted order.
                  mechanism (or (some #(when (contains? gate-map %) %) mechanisms)
                                (first mechanisms))
                  gate (get gate-map mechanism)
                  ;; When the consultation file is lost but the miss ledger
                  ;; survives, the selected row's own :answer (copied into
                  ;; :miss/pegs by build-entry) is the surviving record.
                  explicit (let [from-consults (get explicit-by-id (:id peg))]
                             (cond
                               (seq from-consults) from-consults
                               (some? (:answer peg)) [(:answer peg)]
                               :else []))
                  vocabulary (set (keys (peg-landings peg)))
                  valid-explicit (filterv vocabulary explicit)
                  invalid-explicit (filterv (complement vocabulary) explicit)
                  mech-answers (when gate (gate-answers entries gate))
                  mech-won? (boolean (seq mech-answers))]]
        (cond-> {:peg (:id peg)
                 :mechanism mechanism
                 :collapsed? (branches-collapsed? peg)
                 :answers (cond
                            mech-won? mech-answers
                            (seq valid-explicit) valid-explicit
                            :else [])
                 :answer-source (cond
                                  mech-won? :mechanism
                                  (seq valid-explicit) :explicit
                                  :else nil)
                 :observed? (boolean (or mech-won? (seq valid-explicit)))}
          (seq invalid-explicit) (assoc :invalid-answers invalid-explicit)
          (and mech-won? (seq valid-explicit))
          (assoc :explicit-answers valid-explicit))))}))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} peg-telemetry
  "The §7.7 record over every run directory under `checkpoint-root`
   whose records presented pegs, using `nodes` ({id node}) for mechanism
   pointers and `gate-map` for mechanism->gate. Returns
   {:pegs {peg-id record} :runs-with-pegs n :runs-scanned n
    :incomplete-runs n :incomplete-run-dirs [..]} -- an incomplete run's
   surviving observations still count, but the report names the dirs
   whose input is known-partial rather than presenting them as quiet."
  [checkpoint-root nodes gate-map]
  (let [run-dirs (->> (.listFiles (io/file checkpoint-root))
                      (filter #(.isDirectory ^java.io.File %)))
        ;; One pass over the run dirs: observations accumulate into a
        ;; single vector and runs contributing any are counted as we go.
        {:keys [obs runs-with-pegs incomplete]}
        (reduce (fn [acc run-dir]
                  (let [{:keys [observations incomplete?]}
                        (run-observations run-dir nodes gate-map)]
                    (cond-> acc
                      (seq observations) (-> (update :obs into observations)
                                             (update :runs-with-pegs inc))
                      incomplete? (update :incomplete conj (str run-dir)))))
                {:obs [] :runs-with-pegs 0 :incomplete []}
                run-dirs)]
    {:pegs (aggregate obs)
     :runs-with-pegs runs-with-pegs
     :runs-scanned (count run-dirs)
     :incomplete-runs (count incomplete)
     :incomplete-run-dirs incomplete}))
