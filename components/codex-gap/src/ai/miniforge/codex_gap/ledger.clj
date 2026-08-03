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
   evidence-bundle, never in-repo). Append is a single write so a failure
   cannot corrupt prior entries; IO failures are anomalies-as-data because
   a ledger write must never take a phase down with it.

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
   with the payload verbatim from the producer."
  [{:keys [run-id phase signal situation consultation bucket attribution]}]
  {:miss/id (random-uuid)
   :miss/at (str (java.time.Instant/now))
   :miss/run-id run-id
   :miss/phase phase
   :miss/signal signal
   :miss/situation situation
   :miss/consultation consultation
   :miss/bucket bucket
   :miss/attribution attribution})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} append!
  "Append one entry to <dir>/codex-gap-ledger.edn. Returns the entry, or
   {:codex-gap/anomaly :ledger-write-failed ...} — data, never a throw."
  [dir entry]
  (try
    (io/make-parents (io/file dir ledger-filename))
    (spit (io/file dir ledger-filename)
          (str (pr-str entry) "\n")
          :append true)
    entry
    (catch java.io.IOException e
      {:codex-gap/anomaly :ledger-write-failed
       :codex-gap/reason (ex-message e)
       :codex-gap/dir (str dir)})))

(defn ^{:stratum 1} read-ledger
  "All entries from <dir>/codex-gap-ledger.edn, oldest first. Unreadable
   lines are skipped with a count — a corrupt line must not hide the rest.
   Returns {:entries [..] :skipped n}; missing file = no entries, which is
   a true statement about an instrument that has not run."
  [dir]
  (let [f (io/file dir ledger-filename)]
    (if-not (.exists f)
      {:entries [] :skipped 0}
      (let [lines (remove str/blank? (str/split-lines (slurp f)))
            parsed (map (fn [l] (try (edn/read-string l)
                                     (catch Exception _ ::unreadable)))
                        lines)]
        {:entries (vec (remove #(= ::unreadable %) parsed))
         :skipped (count (filter #(= ::unreadable %) parsed))}))))
