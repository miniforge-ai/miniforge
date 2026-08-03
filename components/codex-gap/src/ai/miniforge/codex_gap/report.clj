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
   channel. Rates whose denominators are not crisply defined in v0
   (consultation-match, learning latency) are reported as
   not-instrumented rather than approximated — the vector confesses."
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

(defn ^{:stratum 0} build-report
  "Pure: ledger entries (+ total skipped-line count and run count) and
   optional anchoring stats -> the gap vector. Every slot carries its
   member miss ids."
  [entries {:keys [skipped run-count anchoring]}]
  (let [by-bucket (group-by :miss/bucket entries)
        ids #(mapv :miss/id (get by-bucket % []))
        delivered (get by-bucket :unheeded [])
        pin-reads (frequencies (map #(get-in % [:miss/consultation :pin-read?])
                                    delivered))
        attributed (+ (count (get by-bucket :misrouted []))
                      (count (get by-bucket :undelivered []))
                      (count delivered))]
    {:entry-count (count entries)
     :run-count (or run-count 1)
     :skipped (or skipped 0)
     :coverage {:covered (count (remove #(= :uncovered (:miss/bucket %)) entries))
                :total (count entries)
                :consultation-match-rate :not-instrumented
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
     :learning {:status :not-instrumented
                :open (+ (count (get by-bucket :uncovered []))
                         (count (get by-bucket :review-queue []))
                         (count (get by-bucket :novel [])))}
     :review-queue {:count (count (get by-bucket :review-queue []))
                    :misses (ids :review-queue)}}))

(defn- ^{:stratum 0} members-line [bucket ids]
  (if (seq ids)
    (msg/t :report/bucket-members {:bucket (name bucket)
                                   :ids (str/join ", " (map str ids))})
    (msg/t :report/no-members)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render-report
  "The gap vector as catalog-backed text, one slot per line, member miss
   ids beneath the slots that carry them."
  [{:keys [entry-count run-count skipped coverage routing anchoring
           delivery attention learning review-queue]}]
  (str/join "\n"
    [(msg/t :report/header
            {:entry-count entry-count
             :run-count run-count
             :skipped (if (pos? skipped)
                        (msg/t :report/skipped-suffix {:n skipped})
                        "")})
     (msg/t :report/coverage {:covered (:covered coverage)
                              :total (:total coverage)})
     (members-line :uncovered (:misses coverage))
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
     (msg/t :report/learning {:open (:open learning)})
     (msg/t :report/review-queue {:count (:count review-queue)})
     (members-line :review-queue (:misses review-queue))]))
