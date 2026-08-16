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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} append!
  "Append one entry to <dir>/codex-gap-ledger.edn. Returns the entry, or
   {:codex-gap/anomaly :ledger-write-failed ...} — data, never a throw for
   environmental failures. A nil/blank dir is a programmer error and
   throws (rule 005): callers own dir resolution."
  [dir entry]
  (when (or (nil? dir) (and (string? dir) (str/blank? dir)))
    (throw (IllegalArgumentException. "ledger dir must be non-blank")))
  (try
    (io/make-parents (io/file dir ledger-filename))
    (with-open [w (java.io.FileOutputStream. (io/file dir ledger-filename) true)]
      (.write w (.getBytes (str (pr-str entry) "\n") "UTF-8")))
    entry
    (catch java.io.IOException e (write-anomaly dir e))
    (catch SecurityException e (write-anomaly dir e))))

(defn ^{:stratum 1} read-ledger
  "All entries from <dir>/codex-gap-ledger.edn, oldest first. Unreadable
   lines are skipped with a count — a corrupt line must not hide the rest.
   Returns {:entries [..] :skipped n}; missing file = no entries, which is
   a true statement about an instrument that has not run."
  [dir]
  (when (or (nil? dir) (and (string? dir) (str/blank? dir)))
    (throw (IllegalArgumentException. "ledger dir must be non-blank")))
  (let [f (io/file dir ledger-filename)]
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
