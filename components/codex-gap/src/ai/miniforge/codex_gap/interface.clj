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
(ns ai.miniforge.codex-gap.interface
  "Codex gap instrument (Thesium Codex SPEC T2; work/codex-gap-instrument
   .spec.edn): the miss ledger and its bucket classifier.

   The ideal codex's value stands by construction; this component measures
   where the actual one leaks — every observed failure becomes a ledger
   entry classified at the FIRST leaking rung, and the ledger is what the
   gap report (Task 3) aggregates. The bucket names the fix channel:
   :uncovered -> harvest, :misrouted -> peg admission, :undelivered ->
   plumbing, :unheeded -> delivery form / landing actionability."
  (:require [ai.miniforge.codex-gap.attribute :as attribute]
            [ai.miniforge.codex-gap.classify :as classify]
            [ai.miniforge.codex-gap.ledger :as ledger]
            [ai.miniforge.codex-gap.report :as report]
            [ai.miniforge.codex-gap.retrodict :as retrodict]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} classify
  "Classify a miss entry into its T2 §3.3 bucket, first leak wins.
   See ai.miniforge.codex-gap.classify/classify for inputs. Returns
   {:bucket kw :attribution {:problem :method :confidence}-or-nil};
   :review-queue is a holding state, not a rung bucket."
  [entry consider-resp problems opts]
  (classify/classify entry consider-resp problems opts))

(defn ^{:stratum 0} build-entry
  "Normalize a miss into its ledger shape (ids and timestamps stamped
   here — writers normalize, readers do not)."
  [miss]
  (ledger/build-entry miss))

(defn ^{:stratum 0} record-miss!
  "Append an entry to the run's ledger file under `dir` (the run's
   checkpoint directory, passed in by the caller — this component never
   derives it). Environmental failures return {:codex-gap/anomaly
   :ledger-write-failed ...} data rather than throwing — a ledger write
   must never take a phase down. A nil/blank `dir` IS a throw
   (IllegalArgumentException): that is a caller bug, not an environment."
  [dir entry]
  (ledger/append! dir entry))

(defn ^{:stratum 0} read-ledger
  "All ledger entries under `dir`, oldest first:
   {:entries [..] :skipped n-unreadable-lines}, or
   {:codex-gap/anomaly :ledger-read-failed ...} on an environmental read
   failure. Same nil/blank-dir programmer-error contract as record-miss!."
  [dir]
  (ledger/read-ledger dir))

(defn ^{:stratum 0} load-gate-reason-map
  "The declared mechanical attribution map ([:reason/code :reason/rule-id]
   -> problem id) from the classpath resource."
  []
  (attribute/load-gate-reason-map))

(defn ^{:stratum 0} retrodict
  "T2 §5.4: re-classify recorded misses offline against the codex as it
   is now, mapping :miss/phase through `phase->situation`. Returns
   {:entries [..] :shift {[recorded retrodicted] n}} — the shift table is
   a newly admitted peg's promotion evidence."
  [entries codex-dir phase->situation]
  (retrodict/retrodict entries codex-dir phase->situation))

(defn ^{:stratum 0} build-report
  "Ledger entries + {:skipped :run-count :anchoring} -> the T2 §4 gap
   VECTOR. Every slot carries its member miss ids; no scalar rollup
   exists or may be derived."
  [entries opts]
  (report/build-report entries opts))

(defn ^{:stratum 0} render-report
  "The gap vector as catalog-backed text."
  [report-map]
  (report/render-report report-map))

(defn ^{:stratum 0} codex-anchoring-stats
  "The §4.1.3 anchoring slot from the codex graph, or nil when unreadable."
  [codex-dir]
  (report/codex-anchoring-stats codex-dir))
