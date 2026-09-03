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
(ns ai.miniforge.codex-gap.retrodict
  "T2 §5.4 retrodiction: re-classify recorded misses OFFLINE against the
   codex as it is NOW, with a phase->situation mapping supplied by the
   caller. A ledger row records the bucket the codex earned at the time;
   retrodiction asks what bucket the same signal would earn today — the
   evidence a newly admitted peg needs before promotion (§5.6), and the
   only way a codex-off baseline arm becomes comparable on coverage and
   routing. The classifier is pure over data, so this never re-runs a
   workflow.

   The recorded bucket is preserved as :miss/bucket-recorded; the
   retrodicted bucket replaces :miss/bucket."
  (:require [ai.miniforge.codex.interface :as codex]
            [ai.miniforge.codex-gap.attribute :as attribute]
            [ai.miniforge.codex-gap.classify :as classify]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} codex-problems
  "Attribution corpus: every problem node's {:id :title :open}, or nil
   when the codex is unreadable (the caller decides what that means)."
  [codex-dir]
  (let [{:keys [nodes] :as graph} (codex/load-graph codex-dir)]
    (when-not (:codex/anomaly graph)
      (->> (vals nodes)
           (filter #(= "problem" (:type %)))
           (map #(select-keys % [:id :title :open]))))))

(defn ^{:stratum 0} retrodict-entry
  "Re-classify one entry. `situation` is the caller's mapping for the
   entry's phase (nil keeps the recorded situation); `consider` is a
   memoizable (fn [situation] consider-resp); `classify-fn` is injected
   so the remap is testable without a codex."
  [classify-fn consider problems opts situation entry]
  (let [;; Blank (nil, "", whitespace) means "no mapping" — codex/consider
        ;; throws on blank situation text, and a blank must behave like
        ;; keep-the-recorded-situation, never crash the retrodiction.
        blank->nil (fn [s] (not-empty (some-> s str str/trim)))
        situation (or (blank->nil situation) (blank->nil (:miss/situation entry)))
        consider-resp (when situation (consider situation))
        {:keys [bucket attribution]}
        (classify-fn {:miss/situation situation
                      :miss/consultation (:miss/consultation entry)
                      :miss/signal (:miss/signal entry)}
                     consider-resp problems opts)]
    (assoc entry
           ;; Idempotent over re-runs: the ORIGINAL recorded bucket is the
           ;; datum; a second retrodiction must not overwrite it with the
           ;; first retrodiction's result.
           :miss/bucket-recorded (or (:miss/bucket-recorded entry) (:miss/bucket entry))
           :miss/bucket bucket
           :miss/situation situation
           :miss/attribution attribution
           :miss/retrodicted? true)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retrodict
  "Re-classify `entries` against the codex at `codex-dir`, mapping each
   entry's :miss/phase through `phase->situation` (a map; phases absent
   from it keep their recorded situation). Returns
   {:entries [...] :shift {[recorded retrodicted] count}} — the shift
   table IS the promotion evidence: how many misses moved out of
   :uncovered, and into what."
  [entries codex-dir phase->situation]
  (let [problems (codex-problems codex-dir)
        consider (memoize (fn [situation] (codex/consider codex-dir situation)))
        ;; Loaded once and closed over — classify would otherwise re-read
        ;; and re-parse the gate-reason map for every entry.
        opts {:gate-reason-map (attribute/load-gate-reason-map)}
        out (mapv (fn [e]
                    (retrodict-entry classify/classify consider problems opts
                                     (get phase->situation (:miss/phase e))
                                     e))
                  entries)]
    {:entries out
     :shift (frequencies (map (juxt :miss/bucket-recorded :miss/bucket) out))}))
