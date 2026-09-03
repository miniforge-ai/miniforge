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
   consultation presented, with each answer's landing set) and the run's
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

(defn ^{:stratum 0} branches-collapsed?
  "True when every answer of `peg` lands on the same problem set -- the
   second §4.4.1 signature. A peg with fewer than two answers cannot
   collapse."
  [peg]
  (let [landings (map set (vals (get peg :answers {})))]
    (and (> (count landings) 1)
         (apply = landings))))

(defn ^{:stratum 0} peg-mechanisms
  "Mechanism pointers carried by the problems `peg` can land on, from
   `nodes` ({id node}); empty when none carries one."
  [peg nodes]
  (->> (vals (get peg :answers {}))
       (apply concat)
       (keep #(get-in nodes [% :mechanism]))
       distinct
       vec))

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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load-mechanism-gate-map
  "The mechanism->gate map, or {} when the resource is absent."
  []
  (if-let [r (io/resource mechanism-gate-map-resource)]
    (edn/read-string (slurp r))
    {}))

(defn ^{:stratum 1} read-gate-history
  "Every readable line of a run's gate-history.edn as a map; unreadable
   lines are skipped (a torn last line must not lose the run)."
  [run-dir]
  (let [f (io/file run-dir gate-history-filename)]
    (if-not (.exists f)
      []
      (into []
            (keep (fn [line]
                    (when-not (str/blank? line)
                      (try (edn/read-string {:default (fn [_ v] v)} line)
                           (catch Exception _ nil)))))
            (str/split-lines (slurp f))))))

(defn ^{:stratum 1} aggregate
  "Fold per-run observations into the §7.7 record per peg."
  [observations]
  (into (sorted-map)
        (map (fn [[peg obs]]
               (let [answers (mapcat :answers obs)
                     freqs (frequencies answers)
                     n (count answers)
                     entropy (entropy-bits freqs)
                     collapsed (count (filter :collapsed? obs))]
                 [peg {:runs (count obs)
                       :mechanism (some :mechanism obs)
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
  "Per distinct peg presented in `run-dir`'s ledger: the answers its
   mechanism's gate recorded in that run, or :unobserved."
  [run-dir nodes gate-map]
  (let [pegs (->> (:entries (ledger/read-ledger (str run-dir)))
                  (mapcat :miss/pegs)
                  (filter :id)
                  (reduce (fn [acc p] (if (contains? acc (:id p)) acc (assoc acc (:id p) p))) {})
                  vals)
        history (delay (read-gate-history run-dir))]
    (for [peg pegs
          :let [mechanisms (peg-mechanisms peg nodes)
                gate (some gate-map mechanisms)]]
      {:peg (:id peg)
       :mechanism (first mechanisms)
       :collapsed? (branches-collapsed? peg)
       :answers (if gate (gate-answers @history gate) [])
       :observed? (some? gate)})))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} peg-telemetry
  "The §7.7 record over every run directory under `checkpoint-root`
   whose ledger presented pegs, using `nodes` ({id node}) for mechanism
   pointers and `gate-map` for mechanism->gate. Returns
   {:pegs {peg-id record} :runs-with-pegs n :runs-scanned n}."
  [checkpoint-root nodes gate-map]
  (let [run-dirs (->> (.listFiles (io/file checkpoint-root))
                      (filter #(.isDirectory ^java.io.File %)))
        per-run (map #(vec (run-observations % nodes gate-map)) run-dirs)
        obs (vec (apply concat per-run))]
    {:pegs (aggregate obs)
     :runs-with-pegs (count (filter seq per-run))
     :runs-scanned (count run-dirs)}))
