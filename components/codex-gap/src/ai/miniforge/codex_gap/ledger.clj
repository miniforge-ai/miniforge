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
(ns ai.miniforge.codex-gap.ledger
  "The miss ledger: an append-only EDN log, one pr-str'd entry per line,
   living in the run's checkpoint directory (spec resolution — never
   evidence-bundle, never in-repo). Each append is one small O_APPEND
   write of one line; a failed append cannot rewrite prior entries, and
   the reader tolerates a torn/corrupt line by skipping and counting it —
   that, not write atomicity (which the JVM does not guarantee), is the
   corruption defense. IO failures are anomalies-as-data because a ledger
   write must never take a phase down with it.

   The entry writer NORMALIZES (one canonical location per datum): ids and
   timestamps are stamped here, [:phase :error] shape inconsistencies are
   the caller's to resolve onto :anomaly/category before recording."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ledger-filename "codex-gap-ledger.edn")

(def ^{:stratum 0} consultations-filename "codex-consultations.edn")

(defn ^{:stratum 0} build-consultation-entry
  "Normalize one phase consultation into its durable per-run shape
   (SPEC $7.7.2.1): recorded WHATEVER the phase outcome, because
   zero-entropy detection needs observations from runs where the guarded
   failure never happened -- exactly the runs the miss ledger ignores.
   `consultation` is the $7.4.3 summary off the phase result; its :pegs
   rows already carry :answer (explicit, or nil = presented-unanswered).
   :consultation/answer-log is the $7.7.2 lost-vs-unanswered marker
   (:absent | :recorded | :unreadable): under :unreadable the session's
   answer log existed but did not parse, so a peg row's nil :answer
   means LOST, not declined -- the telemetry reader keeps such runs out
   of its unanswered count."
  [{:keys [run-id phase consultation]}]
  {;; the summary's own identity when it carries one (stamped at
   ;; construction, shared with the leave's miss entries); minted here
   ;; only for pre-identity summaries
   :consultation/id (get consultation :consultation-id (random-uuid))
   :consultation/at (str (java.time.Instant/now))
   :consultation/run-id run-id
   :consultation/phase phase
   :consultation/situation (get consultation :situation)
   :consultation/status (get consultation :status)
   :consultation/pin-read? (get consultation :pin-read?)
   :consultation/answer-log (get consultation :answer-log)
   :consultation/pegs (get consultation :pegs)
   :consultation/unmatched-answers (get consultation :unmatched-answers)})

(defn ^{:stratum 0} build-entry
  "Normalize a miss into its ledger shape. `signal` is
   {:type :review-blocking|:gate-failure|:terminal-anomaly :payload ...}
   with the payload verbatim from the producer.

   The consultation's :pegs — the §7.7 per-peg record (which way each
   presented peg answered, or that it went unanswered, and the landing
   set that followed) — is lifted to :miss/pegs and stripped from the
   stored consultation: one canonical location per datum. This ledger is
   deliberately the §7.7 accrual surface — the retirement trigger
   (T1 §4.4.1) reads answer distributions from here, no new collection
   surface."
  [{:keys [run-id phase signal situation consultation bucket attribution]}]
  {:miss/id (random-uuid)
   :miss/at (str (java.time.Instant/now))
   :miss/run-id run-id
   :miss/phase phase
   :miss/signal signal
   :miss/situation situation
   :miss/consultation (dissoc consultation :pegs)
   :miss/pegs (get consultation :pegs)
   :miss/bucket bucket
   :miss/attribution attribution})

(defn- ^{:stratum 0} write-anomaly [dir e]
  {:codex-gap/anomaly :ledger-write-failed
   :codex-gap/reason (ex-message e)
   :codex-gap/dir (str dir)})

(defn- ^{:stratum 0} read-lines-file
  "All entries from <dir>/<filename>, oldest first. Unreadable lines are
   skipped with a count — a corrupt line must not hide the rest. Returns
   {:entries [..] :skipped n}; missing file = no entries, which is a true
   statement about an instrument that has not run."
  [dir filename]
  (when (or (nil? dir) (and (string? dir) (str/blank? dir)))
    (throw (IllegalArgumentException. "ledger dir must be non-blank")))
  (let [f (io/file dir filename)]
    (if-not (.exists f)
      {:entries [] :skipped 0}
      (try
        ;; streamed, not slurped: the ledger is append-only and unbounded
        (with-open [r (io/reader f)]
          (reduce (fn [acc line]
                    (if (str/blank? line)
                      acc
                      (let [v (try (edn/read-string line)
                                   (catch Exception _ ::unreadable))]
                        (if (= ::unreadable v)
                          (update acc :skipped inc)
                          (update acc :entries conj v)))))
                  {:entries [] :skipped 0}
                  (line-seq r)))
        (catch java.io.IOException e
          {:codex-gap/anomaly :ledger-read-failed
           :codex-gap/reason (ex-message e)
           :codex-gap/dir (str dir)})
        (catch SecurityException e
          {:codex-gap/anomaly :ledger-read-failed
           :codex-gap/reason (ex-message e)
           :codex-gap/dir (str dir)})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} append-line!
  "Append one EDN entry as a line to <dir>/<filename>. Returns the entry,
   or {:codex-gap/anomaly :ledger-write-failed ...} — data, never a throw
   for environmental failures. A nil/blank dir is a programmer error and
   throws (rule 005): callers own dir resolution."
  [dir filename entry]
  (when (or (nil? dir) (and (string? dir) (str/blank? dir)))
    (throw (IllegalArgumentException. "ledger dir must be non-blank")))
  (try
    (io/make-parents (io/file dir filename))
    (with-open [w (java.io.FileOutputStream. (io/file dir filename) true)]
      (.write w (.getBytes (str (pr-str entry) "\n") "UTF-8")))
    entry
    (catch java.io.IOException e (write-anomaly dir e))
    (catch SecurityException e (write-anomaly dir e))))

(defn ^{:stratum 1} read-ledger
  "All miss entries from <dir>/codex-gap-ledger.edn, oldest first (see
   read-lines-file for the skip/anomaly contract)."
  [dir]
  (read-lines-file dir ledger-filename))

(defn ^{:stratum 1} read-consultations
  "All consultation entries from <dir>/codex-consultations.edn, oldest
   first (see read-lines-file for the skip/anomaly contract)."
  [dir]
  (read-lines-file dir consultations-filename))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} append!
  "Append one miss entry to <dir>/codex-gap-ledger.edn (see append-line!
   for the write/anomaly contract)."
  [dir entry]
  (append-line! dir ledger-filename entry))

(defn ^{:stratum 2} record-consultation!
  "Append one consultation entry (build-consultation-entry) to
   <dir>/codex-consultations.edn. Same write/anomaly contract as the miss
   ledger's append!."
  [dir entry]
  (append-line! dir consultations-filename entry))
