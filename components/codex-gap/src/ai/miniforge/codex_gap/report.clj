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
(ns ai.miniforge.codex-gap.report
  "The gap report (T2 §4): ledger entries + codex -> the gap VECTOR.

   Deliberately no scalar rollup anywhere: a single codex-health number
   would let a consumer compare and rank, which is the false comfort T1
   §1.2 prohibits — the components stay separately visible so the absent
   one is noticeable. Every slot links its member misses (§4.2): the
   report IS the improvement agenda, and each bucket names its fix
   channel. Every rate ships beside its counts and member misses, and a
   slot that cannot be measured says why rather than approximating —
   the vector confesses."
  (:require [ai.miniforge.codex-gap.messages :as msg]
            [ai.miniforge.codex.interface :as codex]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} codex-anchoring-stats
  "The §4.1.3 anchoring slot straight off the codex graph, or nil when the
   codex is unreadable (the renderer says so rather than hiding the slot)."
  [codex-dir]
  (let [{:keys [nodes] :as graph} (codex/load-graph codex-dir)]
    (when-not (:codex/anomaly graph)
      (let [problems (filter #(= "problem" (:type %)) (vals nodes))]
        {:problems (count problems)
         :unanchored (count (filter #(empty? (:scars %)) problems))
         :speculative (count (filter #(= "speculative" (:confidence %)) problems))}))))

(def ^{:stratum 0} attempt-anomalies
  "Skip causes where the codex was read but no single board matched the
   situation: the consultation was attempted and missed (T2 §4.1.1)."
  #{:situation-unmatched :situation-ambiguous})

(defn- ^{:stratum 0} consultation-kind
  [{:consultation/keys [status anomaly]}]
  (cond
    (= :pinned status)                    :matched
    (not= :skipped status)                :not-attempted
    (contains? attempt-anomalies anomaly) :unmatched
    (nil? anomaly)                        :unclassified
    :else                                 :not-attempted))

(defn- ^{:stratum 0} consultation-match
  "§4.1.1 match rate. :rate is nil when nothing was attempted; a :skipped
   entry with no recorded anomaly is :unclassified, never an attempt.
   :unreadable counts runs whose consultations could not be read, so a
   rate over the rest is marked incomplete rather than passed as whole."
  [consultations unreadable]
  (let [by-kind   (group-by consultation-kind consultations)
        matched   (count (get by-kind :matched))
        unmatched (get by-kind :unmatched [])
        attempted (+ matched (count unmatched))
        rate      (when (pos? attempted) (/ (double matched) attempted))]
    {:matched      matched
     :attempted    attempted
     :unclassified (count (get by-kind :unclassified))
     :unreadable   unreadable
     :rate         rate
     :unmatched    (mapv :consultation/situation unmatched)}))

(defn ^{:stratum 0} harvest-decisions
  "The codex's harvest decisions, or nil when they cannot be read."
  [codex-dir]
  (:decisions (codex/harvest-decisions codex-dir)))

(defn- ^{:stratum 0} miss-date [entry]
  (let [at (:miss/at entry)]
    (when (string? at)
      (try (-> (java.time.Instant/parse at)
               (.atZone java.time.ZoneOffset/UTC)
               .toLocalDate)
           (catch java.time.format.DateTimeParseException _ nil)))))

(defn- ^{:stratum 0} earlier-date [current candidate]
  (if (and current (not (.isAfter ^java.time.LocalDate current candidate)))
    current
    candidate))

(defn- ^{:stratum 0} keep-earliest [acc [miss-id decided]]
  (update acc miss-id earlier-date decided))

(defn- ^{:stratum 0} decision-dates
  "miss-id -> the earliest decision that names it."
  [decisions]
  (reduce keep-earliest {}
          (for [{:keys [decided misses]} decisions, miss-id misses]
            [miss-id decided])))

(defn- ^{:stratum 0} median [xs]
  (when (seq xs)
    (let [v   (vec (sort xs))
          mid (quot (count v) 2)]
      (if (odd? (count v))
        (double (v mid))
        (/ (+ (v (dec mid)) (v mid)) 2.0)))))

(defn- ^{:stratum 0} latency-days [dates entry]
  (let [decided  (get dates (:miss/id entry))
        recorded (miss-date entry)]
    (when (and decided recorded)
      (.between java.time.temporal.ChronoUnit/DAYS recorded decided))))

(defn- ^{:stratum 0} decided-by?
  "A decision names the miss, and the miss counts toward rates: a
   :review-queue entry stays out of every rate until resolved (T2 §3.4)."
  [dates entry]
  (and (not= :review-queue (:miss/bucket entry))
       (contains? dates (:miss/id entry))))

(defn- ^{:stratum 0} learning-slot
  "§4.1.6: median days from miss to harvest decision over decided misses,
   and the open candidates no decision names. nil `harvest` means the
   harvest records could not be read."
  [entries open-candidates harvest]
  (if (nil? harvest)
    {:status  :harvest-unavailable
     :open    (count open-candidates)
     :misses  (mapv :miss/id open-candidates)}
    (let [dates     (decision-dates harvest)
          decided   (filter (partial decided-by? dates) entries)
          latencies (keep (partial latency-days dates) decided)
          undated   (- (count decided) (count latencies))
          open      (remove (partial decided-by? dates) open-candidates)]
      {:status         :measured
       :decided        (count decided)
       :undated        undated
       :median-days    (median latencies)
       :decided-misses (mapv :miss/id decided)
       :open           (count open)
       :misses         (mapv :miss/id open)})))

(defn ^{:stratum 0} build-report
  "Pure: ledger entries (+ total skipped-line count, run count, the runs'
   consultation entries, harvest decisions) and optional anchoring stats
   -> the gap vector. Every slot carries its member miss ids.
   :unreadable-ledgers counts runs whose miss ledger was not fully read;
   it sits at the top level because every slot is computed from those
   entries, so the whole report, not one slot, is incomplete."
  [entries {:keys [skipped run-count anchoring consultations
                   unreadable-consultations unreadable-ledgers harvest]
            :or   {unreadable-consultations 0
                   unreadable-ledgers       0}}]
  (let [by-bucket (group-by :miss/bucket entries)
        ids #(mapv :miss/id (get by-bucket % []))
        delivered (get by-bucket :unheeded [])
        pin-reads (frequencies (map #(get-in % [:miss/consultation :pin-read?])
                                    delivered))
        open-candidates (mapcat #(get by-bucket % []) [:uncovered :review-queue :novel])
        attributed (+ (count (get by-bucket :misrouted []))
                      (count (get by-bucket :undelivered []))
                      (count delivered))]
    {:entry-count (count entries)
     :run-count (or run-count 1)
     :skipped (or skipped 0)
     :unreadable-ledgers unreadable-ledgers
     :coverage {:covered (count (remove #(= :uncovered (:miss/bucket %)) entries))
                :total (count entries)
                :consultation-match (consultation-match consultations
                                                        unreadable-consultations)
                :misses (ids :uncovered)}
     :routing {:misrouted (count (get by-bucket :misrouted []))
               :attributed attributed
               :misses (ids :misrouted)}
     :anchoring (or anchoring :codex-unavailable)
     :delivery {:undelivered (count (get by-bucket :undelivered []))
                :pin-read {:read (get pin-reads true 0)
                           :unread (get pin-reads false 0)
                           :unknown (get pin-reads nil 0)}
                :misses (ids :undelivered)}
     :attention {:unheeded (count delivered)
                 :misses (ids :unheeded)}
     :learning (learning-slot entries open-candidates harvest)
     :review-queue {:count (count (get by-bucket :review-queue []))
                    :misses (ids :review-queue)}}))

(defn- ^{:stratum 0} match-text
  [{:keys [matched attempted unclassified unreadable rate]}]
  (let [percent (some-> rate (* 100.0) Math/round)]
    (str (if rate
           (msg/t :report/match-rate {:matched   matched
                                      :attempted attempted
                                      :percent   percent})
           (msg/t :report/match-none))
         (if (pos? unclassified)
           (msg/t :report/match-unclassified-suffix {:n unclassified})
           "")
         (if (pos? unreadable)
           (msg/t :report/match-incomplete-suffix {:n unreadable})
           ""))))

(defn- ^{:stratum 0} undated-suffix [undated]
  (if (pos? undated)
    (msg/t :report/learning-undated-suffix {:n undated})
    ""))

(defn- ^{:stratum 0} ledger-incomplete-suffix
  "Runs whose miss ledger was not fully read: every slot below is computed
   over the entries that were, so the header says the report is partial."
  [unreadable-ledgers]
  (if (pos? unreadable-ledgers)
    (msg/t :report/ledger-incomplete-suffix {:n unreadable-ledgers})
    ""))

(defn- ^{:stratum 0} learning-text
  [{:keys [status decided undated median-days open]}]
  (cond
    (= :harvest-unavailable status) (msg/t :report/learning-unavailable {:open open})
    (zero? decided)                 (msg/t :report/learning-none-decided {:open open})
    (nil? median-days)              (msg/t :report/learning-undated {:decided decided
                                                                     :open    open})
    :else                           (msg/t :report/learning {:decided decided
                                                             :median  median-days
                                                             :undated (undated-suffix undated)
                                                             :open    open})))

(defn- ^{:stratum 0} members-line [bucket ids]
  (if (seq ids)
    (msg/t :report/bucket-members {:bucket (name bucket)
                                   :ids (str/join ", " (map str ids))})
    (msg/t :report/no-members)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render-report
  "The gap vector as catalog-backed text, one slot per line, member miss
   ids beneath the slots that carry them."
  [{:keys [entry-count run-count skipped unreadable-ledgers coverage routing
           anchoring delivery attention learning review-queue]}]
  (str/join "\n"
    [(msg/t :report/header
            {:entry-count entry-count
             :run-count run-count
             :skipped (if (pos? skipped)
                        (msg/t :report/skipped-suffix {:n skipped})
                        "")
             :incomplete (ledger-incomplete-suffix unreadable-ledgers)})
     (msg/t :report/coverage {:covered (:covered coverage)
                              :total (:total coverage)
                              :match (match-text (:consultation-match coverage))})
     (members-line :uncovered (:misses coverage))
     (members-line :unmatched (get-in coverage [:consultation-match :unmatched]))
     (msg/t :report/routing {:misrouted (:misrouted routing)
                             :attributed (:attributed routing)})
     (members-line :misrouted (:misses routing))
     (if (map? anchoring)
       (msg/t :report/anchoring anchoring)
       (msg/t :report/anchoring-unavailable))
     (msg/t :report/delivery {:undelivered (:undelivered delivery)
                              :read (get-in delivery [:pin-read :read])
                              :unread (get-in delivery [:pin-read :unread])
                              :unknown (get-in delivery [:pin-read :unknown])})
     (members-line :undelivered (:misses delivery))
     (msg/t :report/attention {:unheeded (:unheeded attention)})
     (members-line :unheeded (:misses attention))
     (learning-text learning)
     (members-line :learning (:misses learning))
     (members-line :decided (:decided-misses learning))
     (msg/t :report/review-queue {:count (:count review-queue)})
     (members-line :review-queue (:misses review-queue))]))
