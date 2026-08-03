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
(ns ai.miniforge.phase-software-factory.gap-wiring
  "Miss-ledger recording at phase leave (gap-instrument spec Task 2, T2
   §3). Collects the phase's failure signals — review blocking issues
   (map-shaped :review/issues preferred, string fallback), gate failures
   (decision-envelope Reason maps off the NAMESPACED [:phase :phase/...]
   keys), and terminal anomalies (normalized onto :anomaly/category at
   this writer) — classifies each against the live codex, and appends to
   the run's ledger.

   Recording is opt-in by construction (no MINIFORGE_CODEX_PATH -> no-op:
   without a codex there is no gap to measure) and best-effort by
   contract: a recording failure logs and continues, because the
   instrument must never change the outcome it measures."
  (:require [ai.miniforge.codex-gap.interface :as gap]
            [ai.miniforge.codex.interface :as codex]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]))

;------------------------------------------------------------------------------ Layer 0

;; Signal extraction — pure over ctx fragments, public for tests
(defn ^{:stratum 0} review-signals
  "Blocking review findings as miss signals. Prefers the map-shaped
   :review/issues (severity/file/description); falls back to the
   :review/blocking-issues string vector, which is all the gate-only and
   parse-failure paths populate."
  [output]
  (let [issues (seq (filter #(= :blocking (:severity %)) (:review/issues output)))]
    (if issues
      (map (fn [i] {:type :review-blocking :payload i}) issues)
      (map (fn [s] {:type :review-blocking :payload s})
           (:review/blocking-issues output)))))

(defn ^{:stratum 0} gate-signals
  "Gate failures as miss signals, one per decision-envelope Reason map.
   Reads the NAMESPACED keys: bare [:phase :status] is clobbered at leave."
  [phase-map]
  (when (= :failed (:phase/status phase-map))
    (map (fn [r] {:type :gate-failure :payload r})
         (:phase/gate-errors phase-map))))

(defn ^{:stratum 0} terminal-signal
  "The phase's terminal anomaly as a miss signal, normalized here (the
   writer) onto :anomaly/category — [:phase :error] shapes are
   inconsistent across producers (sub-anomaly records vs hand-rolled
   maps), and readers must not each re-solve that."
  [phase-map]
  (when-let [err (:error phase-map)]
    {:type :terminal-anomaly
     :payload {:anomaly/category (or (:anomaly/category err)
                                     (get-in err [:anomaly :category]))
               :anomaly/message (or (:anomaly/message err) (:message err))}}))

(defn- ^{:stratum 0} ledger-dir
  "The run's checkpoint dir, or nil when identity/root are absent (e.g.
   bare unit-test ctxs) — recording skips rather than inventing a path."
  [ctx]
  (let [root (:execution/checkpoint-root ctx)
        run-id (:execution/id ctx)]
    (when (and root run-id)
      (str (java.io.File. (str root) (str run-id))))))

(defn- ^{:stratum 0} codex-problems
  "Attribution corpus: every problem node's {:id :title :open}."
  [codex-dir]
  (let [{:keys [nodes] :as graph} (codex/load-graph codex-dir)]
    (when-not (:codex/anomaly graph)
      (->> (vals nodes)
           (filter #(= "problem" (:type %)))
           (map #(select-keys % [:id :title :open]))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-signals
  "All miss signals visible on ctx at leave for `_phase`."
  [ctx _phase]
  (let [phase-map (get ctx :phase)
        output (get-in phase-map [:result :output])]
    (concat (review-signals output)
            (gate-signals phase-map)
            (when-let [t (terminal-signal phase-map)] [t]))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record-phase-misses!
  "Classify and append every failure signal of the phase at leave.
   No-op without a configured codex or a resolvable run dir. Never
   affects the phase: environmental failures come back as data from the
   ledger, and anything unexpected logs and continues — except an
   interrupt, which must keep flying (cooperative cancellation)."
  ([ctx phase] (record-phase-misses! ctx phase (codex-pin/configured-codex-dir)))
  ([ctx phase codex-dir]
   (let [logger (:execution/logger ctx)
         dir (ledger-dir ctx)]
     (when (and codex-dir dir)
       (try
         (let [phase-map (get ctx :phase)
               consultation (get-in phase-map [:result :output :codex/consultation])
               situation (or (:situation consultation)
                             (get codex-pin/phase->situation phase))
               consider-resp (when situation (codex/consider codex-dir situation))
               problems (codex-problems codex-dir)]
           (doseq [signal (phase-signals ctx phase)]
             (let [{:keys [bucket attribution queue-reason]}
                   (gap/classify {:miss/situation situation
                                  :miss/consultation consultation
                                  :miss/signal signal}
                                 consider-resp problems {})
                   entry (gap/build-entry {:run-id (:execution/id ctx)
                                           :phase phase
                                           :signal signal
                                           :situation situation
                                           :consultation consultation
                                           :bucket bucket
                                           :attribution (cond-> attribution
                                                          queue-reason
                                                          (assoc :queue-reason queue-reason))})
                   written (gap/record-miss! dir entry)]
               (when (and logger (:codex-gap/anomaly written))
                 (log/warn logger phase :codex-gap/ledger-write-failed
                           {:data written})))))
         (catch InterruptedException e (throw e))
         ;; Boundary catch, deliberate and narrow in effect: the instrument
         ;; must never change the outcome it measures, so an unexpected
         ;; classification bug logs and the phase proceeds. Interrupts are
         ;; rethrown above — this must not eat cooperative cancellation.
         (catch Exception e
           (when logger
             (log/warn logger phase :codex-gap/recording-failed
                       {:data {:error (ex-message e)}}))))))))
