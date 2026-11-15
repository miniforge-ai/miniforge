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
(ns ai.miniforge.codex-gap.interface-test
  "Signal payload shapes mirror the real producers: ReviewIssue maps
   (agent reviewer issues schema), decision-envelope Reason maps, and
   codex consider-response landings (pinned by the codex component's
   tests). Problems corpus entries mirror codex load-graph problem nodes
   (:id :title :open with {:value} items)."
  (:require [ai.miniforge.codex-gap.interface :as gap]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} problems
  [{:id "contract-drift-is-silent"
    :title "Nothing enforces agreement between a producer and its consumers"
    :open [{:value "when producer and consumer live in different repos, the verification has no single place to live"}]}
   {:id "liveness-detection"
    :title "Liveness cannot be inferred from activity"
    :open [{:value "progress is domain-specific; there is no universal signal"}]}])

(def ^{:stratum 0} boundary-landings
  ;; consider-resp for changing-one-side-of-a-boundary: contract drift IS
  ;; a landing; liveness-detection is NOT reachable from that board.
  {:landings [{:id "contract-drift-is-silent" :type "problem"}]})

(defn- ^{:stratum 0} entry [situation consultation signal]
  {:miss/situation situation
   :miss/consultation consultation
   :miss/signal signal})

(def ^{:stratum 0} drift-signal
  {:type :review-blocking
   :payload {:severity :blocking
             :description "consumer reads a key the producer never writes — nothing enforces agreement between producer and consumer"}})

(deftest ^{:stratum 0} missing-ledger-reads-as-empty-not-error
  (is (= {:entries [] :skipped 0} (gap/read-ledger "/nonexistent/run/dir"))))

(deftest ^{:stratum 0} gap-report-confesses-unavailable-anchoring
  (let [r (gap/build-report [] {})]
    (is (= :codex-unavailable (:anchoring r)))
    (is (re-find #"anchoring: codex unavailable" (gap/render-report r)))))

(deftest ^{:stratum 0} consultation-record-round-trips-beside-the-miss-ledger
  ;; SPEC §7.7.2.1: consultations accrue per run whatever the outcome —
  ;; the file the retirement trigger reads for zero-entropy evidence from
  ;; clean runs. Same append/read machinery as the miss ledger, separate
  ;; file, so neither contaminates the other.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "codex-gap-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))
        entry (gap/build-consultation-entry
               {:run-id "run-1" :phase :implement
                :consultation {:status :pinned :pin-read? true
                               :situation "changing-one-side-of-a-boundary"
                               :answer-log :recorded
                               :pegs [{:id "peg-a" :answer "no"
                                       :landings {"yes" ["p1"] "no" ["p2"]}}]
                               :unmatched-answers nil}})]
    (is (uuid? (:consultation/id entry)))
    (is (= "no" (get-in entry [:consultation/pegs 0 :answer]))
        "the explicit answer survives into the durable shape")
    (is (= :recorded (:consultation/answer-log entry))
        "the §7.7.2 lost-vs-unanswered marker survives into the durable shape")
    (is (= entry (gap/record-consultation! dir entry)))
    (is (= {:entries [] :skipped 0} (gap/read-ledger dir))
        "a consultation is not a miss — the miss ledger stays empty")
    (let [{:keys [entries skipped]} (gap/read-consultations dir)]
      (is (= [entry] entries))
      (is (= 0 skipped)))))

(def ^{:stratum 0} unmatched-situation "about-to-commit-consequential")

;; Built through the producer, from the summary shape codex-pin emits.
(defn- ^{:stratum 0} consultation [status anomaly situation]
  (gap/build-consultation-entry
   {:run-id "run-1" :phase :plan
    :consultation {:status status :anomaly anomaly :situation situation}}))

(def ^{:stratum 0} pinned
  (consultation :pinned nil "changing-one-side-of-a-boundary"))

;; 3 matched, 1 unmatched attempt, then an unreadable codex and 2 unmapped phases.
(def ^{:stratum 0} mixed-consultations
  (concat (repeat 3 pinned)
          [(consultation :skipped :situation-unmatched unmatched-situation)
           (consultation :skipped :codex-unreadable "s")]
          (repeat 2 (consultation :unmapped nil nil))))

(defn- ^{:stratum 0} miss-at
  "A ledger miss in the producer's shape, recorded at `instant`."
  [bucket instant]
  (assoc (gap/build-entry {:run-id "r" :phase :implement :signal drift-signal
                           :situation nil :consultation nil
                           :bucket bucket :attribution nil})
         :miss/at instant))

;; The shape codex/harvest-decisions returns.
(defn- ^{:stratum 0} decision [date outcome & miss-ids]
  {:decided (java.time.LocalDate/parse date) :outcome outcome :misses (vec miss-ids)})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} no-situation-classifies-uncovered-first
  (testing "rung 1 wins even when delivery also failed"
    (is (= :uncovered
           (:bucket (gap/classify (entry nil nil drift-signal)
                                  nil problems {}))))))

(deftest ^{:stratum 1} confident-unreachable-attribution-is-misrouted-even-when-undelivered
  (let [signal {:type :review-blocking
                :payload {:severity :blocking
                          :description "liveness cannot be inferred from activity; heartbeats reset the clock"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :skipped :pinned? false :pin-read? nil}
                             signal)
                      boundary-landings problems {})]
    (is (= :misrouted bucket)
        "the board could not have reached it — earlier leak than delivery")
    (is (= "liveness-detection" (:problem attribution)))
    (is (= :similarity (:method attribution)))))

(deftest ^{:stratum 1} skipped-consultation-on-a-reachable-problem-is-undelivered
  (is (= :undelivered
         (:bucket (gap/classify (entry "changing-one-side-of-a-boundary"
                                       {:status :skipped :pinned? false :pin-read? nil}
                                       drift-signal)
                                boundary-landings problems {})))))

(deftest ^{:stratum 1} nil-consultation-with-mapped-situation-is-undelivered
  (is (= :undelivered
         (:bucket (gap/classify (entry "changing-one-side-of-a-boundary"
                                       nil drift-signal)
                                boundary-landings problems {})))))

(deftest ^{:stratum 1} delivered-and-matching-a-landing-is-unheeded
  (let [{:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :pinned :pinned? true :pin-read? nil}
                             drift-signal)
                      boundary-landings problems {})]
    (is (= :unheeded bucket))
    (is (= "contract-drift-is-silent" (:problem attribution)))))

(deftest ^{:stratum 1} below-threshold-goes-to-the-review-queue
  (let [signal {:type :review-blocking
                :payload {:severity :blocking
                          :description "flaky wifi on the build machine"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :pinned :pinned? true :pin-read? true}
                             signal)
                      boundary-landings problems {})]
    (is (= :review-queue bucket))
    (is (some? attribution) "the best candidate travels with the queued entry")
    (is (< (:confidence attribution) 0.5))))

(deftest ^{:stratum 1} unavailable-landings-queue-instead-of-convicting-misrouted
  ;; nil or anomaly consider-resp makes rung 2 UNPROVABLE — an empty landing
  ;; set must not convict a confident attribution of :misrouted.
  (doseq [resp [nil {:codex/anomaly :codex-unreadable :codex/reason "gone"}]]
    (let [{:keys [bucket queue-reason attribution]}
          (gap/classify (entry "changing-one-side-of-a-boundary"
                               {:status :pinned :pinned? true :pin-read? true}
                               drift-signal)
                        resp problems {})]
      (is (= :review-queue bucket))
      (is (= :landings-unavailable queue-reason))
      (is (= "contract-drift-is-silent" (:problem attribution))))))

(deftest ^{:stratum 1} mechanical-attribution-wins-for-typed-gate-reasons
  (let [signal {:type :gate-failure
                :payload {:reason/code :reason/grant-absent
                          :reason/detail "no enablement found for repo"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "agent-acts-in-the-world"
                             {:status :pinned :pinned? true :pin-read? true}
                             signal)
                      {:landings [{:id "fail-closed-by-default" :type "problem"}]}
                      problems {})]
    (is (= :mechanical (:method attribution)))
    (is (= "fail-closed-by-default" (:problem attribution)))
    (is (= 1.0 (:confidence attribution)))
    (is (= :unheeded bucket))))

(deftest ^{:stratum 1} policy-rule-violations-attribute-to-enforcement-after-authoring
  ;; The observational matrix's dominant miss shape: a policy-pack rule
  ;; violation at verify. Code-wide mapping — WHEN the rule reached the
  ;; author is the problem, not which rule.
  (let [signal {:type :gate-failure
                :payload {:reason/code :reason/rule-violation
                          :reason/rule-id :std/config-as-data
                          :reason/detail "policy rule violated"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "submitting-work-to-enforced-gates"
                             {:status :pinned :pinned? true :pin-read? true}
                             signal)
                      {:landings [{:id "enforcement-after-authoring" :type "problem"}]}
                      problems {})]
    (is (= :mechanical (:method attribution)))
    (is (= "enforcement-after-authoring" (:problem attribution)))
    (is (= :unheeded bucket))))

(deftest ^{:stratum 1} ledger-round-trips-and-survives-corrupt-lines
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "codex-gap-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))
        e1 (gap/build-entry {:run-id "run-1" :phase :implement
                             :signal drift-signal
                             :situation "changing-one-side-of-a-boundary"
                             :consultation {:status :pinned :pinned? true :pin-read? nil}
                             :bucket :unheeded
                             :attribution {:problem "contract-drift-is-silent"
                                           :method :similarity :confidence 0.61}})]
    (is (= e1 (gap/record-miss! dir e1)))
    (spit (io/file dir "codex-gap-ledger.edn") "{corrupt\n" :append true)
    (let [e2 (gap/build-entry {:run-id "run-1" :phase :review
                               :signal drift-signal :situation nil
                               :consultation nil :bucket :uncovered
                               :attribution nil})]
      (is (= e2 (gap/record-miss! dir e2)))
      (let [{:keys [entries skipped]} (gap/read-ledger dir)]
        (is (= [e1 e2] entries) "corrupt line skipped, order preserved")
        (is (= 1 skipped))
        (is (nil? (get-in entries [0 :miss/consultation :pin-read?]))
            "tri-state pin-read? survives the round trip verbatim")))))

(deftest ^{:stratum 1} build-entry-lifts-pegs-out-of-the-consultation
  ;; SPEC §7.7: the per-peg record accrues in this ledger. The writer
  ;; normalizes — pegs live at :miss/pegs only, never duplicated inside
  ;; the stored consultation.
  (let [pegs [{:id "peg-a" :answer nil :landings {"yes" ["p1"] "no" ["p2"]}}]
        e (gap/build-entry {:run-id "r" :phase :implement
                            :signal drift-signal
                            :situation "changing-one-side-of-a-boundary"
                            :consultation {:status :pinned :pinned? true
                                           :pin-read? nil :pegs pegs}
                            :bucket :unheeded :attribution nil})]
    (is (= pegs (:miss/pegs e)))
    (is (= {:status :pinned :pinned? true :pin-read? nil}
           (:miss/consultation e))
        "one canonical location: the stored consultation drops :pegs")
    (testing "a peg-less consultation stays nil across both keys"
      (let [e (gap/build-entry {:run-id "r" :phase :review
                                :signal drift-signal :situation nil
                                :consultation nil :bucket :uncovered
                                :attribution nil})]
        (is (nil? (:miss/pegs e)))
        (is (nil? (:miss/consultation e)))))))

(deftest ^{:stratum 1} ledger-write-failure-is-data-not-a-throw
  (let [r (gap/record-miss! "/dev/null/not-a-dir" (gap/build-entry
                                                    {:run-id "x" :phase :implement
                                                     :signal drift-signal :situation nil
                                                     :consultation nil :bucket :uncovered
                                                     :attribution nil}))]
    (is (= :ledger-write-failed (:codex-gap/anomaly r)))))

(deftest ^{:stratum 1} gap-report-is-a-vector-with-member-ids-and-no-scalar
  (let [mk (fn [bucket pin-read]
             (gap/build-entry {:run-id "r" :phase :implement
                               :signal drift-signal
                               :situation "s" :consultation {:status :pinned :pin-read? pin-read}
                               :bucket bucket :attribution nil}))
        entries [(mk :uncovered nil) (mk :misrouted nil) (mk :undelivered nil)
                 (mk :unheeded true) (mk :unheeded nil) (mk :review-queue nil)]
        r (gap/build-report entries {:skipped 1 :run-count 2 :anchoring
                                     {:problems 22 :unanchored 4 :speculative 5}})]
    (testing "slot counts"
      (is (= {:covered 5 :total 6} (select-keys (:coverage r) [:covered :total])))
      (is (= 1 (get-in r [:routing :misrouted])))
      (is (= 4 (get-in r [:routing :attributed])))
      (is (= {:read 1 :unread 0 :unknown 1} (get-in r [:delivery :pin-read])))
      (is (= 2 (get-in r [:attention :unheeded])))
      (is (= 2 (get-in r [:learning :open])))
      (is (= 1 (get-in r [:review-queue :count]))))
    (testing "every slot links member miss ids (T2 §4.2)"
      (is (= 1 (count (get-in r [:coverage :misses]))))
      (is (= 1 (count (get-in r [:routing :misses]))))
      (is (= 1 (count (get-in r [:delivery :misses]))))
      (is (= 2 (count (get-in r [:attention :misses]))))
      (is (= 2 (count (get-in r [:learning :misses]))))
      (is (= 1 (count (get-in r [:review-queue :misses])))))
    (testing "no scalar rollup key exists"
      (is (nil? (:score r)))
      (is (nil? (:health r))))
    (testing "renders through the catalog with slots and members"
      (let [text (gap/render-report r)]
        (is (re-find #"Codex gap report — 6 misses across 2 run" text))
        (is (re-find #"coverage: 5 of 6" text))
        (is (re-find #"routing: 1 of 4" text))
        (is (re-find #"anchoring: 22 problems, 4 unanchored, 5 speculative" text))
        (is (re-find #"review queue: 1" text))))))

(deftest ^{:stratum 1} consultation-entry-records-the-skip-cause
  (is (= :situation-unmatched
         (:consultation/anomaly
          (consultation :skipped :situation-unmatched unmatched-situation))))
  (is (nil? (:consultation/anomaly pinned))))

(deftest ^{:stratum 1} coverage-reports-matched-of-attempted-consultations
  (let [r     (gap/build-report [] {:consultations mixed-consultations})
        match (get-in r [:coverage :consultation-match])
        text  (gap/render-report r)]
    (testing "an unreadable codex and an unmapped phase are not attempts"
      (is (= 3 (:matched match)))
      (is (= 4 (:attempted match)))
      (is (= 0.75 (:rate match))))
    (testing "the unmatched attempt's situation is the slot's member"
      (is (= [unmatched-situation] (:unmatched match)))
      (is (re-find (re-pattern (str "unmatched: " unmatched-situation)) text)))
    (is (re-find #"3 of 4 attempted consultations matched a situation \(75%\)" text))))

(deftest ^{:stratum 1} coverage-sets-pre-anomaly-skips-apart
  (let [r     (gap/build-report [] {:consultations [pinned (consultation :skipped nil "s")]})
        match (get-in r [:coverage :consultation-match])]
    (is (= {:matched 1 :attempted 1 :unclassified 1}
           (select-keys match [:matched :attempted :unclassified])))
    (is (re-find #"1 older skipped consultation\(s\) unclassified" (gap/render-report r)))))

(deftest ^{:stratum 1} coverage-with-no-attempts-reports-no-rate
  (let [r (gap/build-report [] {:consultations [(consultation :unconfigured nil nil)]})]
    (is (nil? (get-in r [:coverage :consultation-match :rate])))
    (is (re-find #"no consultation attempted" (gap/render-report r)))))

(deftest ^{:stratum 1} unreadable-consultations-mark-the-rate-incomplete
  (let [r (gap/build-report [] {:consultations [pinned] :unreadable-consultations 2})]
    (is (= 2 (get-in r [:coverage :consultation-match :unreadable])))
    (is (re-find #"INCOMPLETE: consultations unreadable in 2 run" (gap/render-report r))))
  (testing "nothing attempted but files unreadable is still flagged"
    (is (re-find #"no consultation attempted, INCOMPLETE"
                 (gap/render-report (gap/build-report [] {:unreadable-consultations 1}))))))

(deftest ^{:stratum 1} a-read-that-lost-entries-is-incomplete
  (is (true? (gap/incomplete-read? {:entries [pinned] :skipped 1})))
  (is (true? (gap/incomplete-read? {:codex-gap/anomaly :ledger-read-failed})))
  (is (false? (gap/incomplete-read? {:entries [pinned] :skipped 0}))))

(deftest ^{:stratum 1} recorded-consultations-feed-the-coverage-rate
  ;; The bb codex-gap-report path: entries recorded per run, read back, reported.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "codex-gap-match-"
                   (into-array java.nio.file.attribute.FileAttribute [])))]
    (gap/record-consultation! dir pinned)
    (gap/record-consultation! dir (consultation :skipped :situation-ambiguous unmatched-situation))
    (let [consultations (:entries (gap/read-consultations dir))
          text (gap/render-report (gap/build-report [] {:consultations consultations}))]
      (is (re-find #"1 of 2 attempted consultations matched a situation \(50%\)" text)))))

(deftest ^{:stratum 1} learning-pairs-a-miss-with-its-harvest-decision
  (let [miss (miss-at :uncovered "2026-09-01T10:00:00Z")
        r    (gap/build-report [miss] {:harvest [(decision "2026-09-04" :admitted (:miss/id miss))]})]
    (is (= {:status :measured :decided 1 :median-days 3.0 :open 0}
           (select-keys (:learning r) [:status :decided :median-days :open])))
    (is (re-find #"learning: 1 miss\(es\) decided, median 3.0 day" (gap/render-report r)))))

(deftest ^{:stratum 1} learning-counts-a-refusal-as-a-decision
  (let [miss (miss-at :uncovered "2026-09-01T10:00:00Z")
        r    (gap/build-report [miss] {:harvest [(decision "2026-09-02" :refused (:miss/id miss))]})]
    (is (= 1 (get-in r [:learning :decided])))
    (is (= 1.0 (get-in r [:learning :median-days])))))

(deftest ^{:stratum 1} learning-uses-the-earliest-decision-naming-a-miss
  (let [miss (miss-at :uncovered "2026-09-01T10:00:00Z")
        r    (gap/build-report [miss] {:harvest [(decision "2026-09-10" :admitted (:miss/id miss))
                                                 (decision "2026-09-05" :refused (:miss/id miss))]})]
    (is (= 4.0 (get-in r [:learning :median-days])))))

(deftest ^{:stratum 1} learning-keeps-unnamed-misses-open
  (let [named   (miss-at :uncovered "2026-09-01T10:00:00Z")
        unnamed (miss-at :uncovered "2026-09-02T10:00:00Z")
        r       (gap/build-report [named unnamed]
                                  {:harvest [(decision "2026-09-04" :admitted (:miss/id named))]})]
    (is (= 1 (get-in r [:learning :open])))
    (is (= [(:miss/id unnamed)] (get-in r [:learning :misses])))))

(deftest ^{:stratum 1} learning-without-harvest-records-reports-unavailable
  (let [r (gap/build-report [(miss-at :uncovered "2026-09-01T10:00:00Z")] {})]
    (is (= :harvest-unavailable (get-in r [:learning :status])))
    (is (nil? (get-in r [:learning :median-days])))
    (is (re-find #"learning: harvest records unavailable; 1 open" (gap/render-report r)))))

(deftest ^{:stratum 1} learning-lists-the-decided-misses
  (let [miss (miss-at :uncovered "2026-09-01T10:00:00Z")
        r    (gap/build-report [miss] {:harvest [(decision "2026-09-04" :admitted (:miss/id miss))]})]
    (is (= [(:miss/id miss)] (get-in r [:learning :decided-misses])))
    (is (re-find (re-pattern (str "decided: " (:miss/id miss))) (gap/render-report r)))))

(deftest ^{:stratum 1} learning-keeps-review-queue-misses-out-of-the-rate
  (let [queued (miss-at :review-queue "2026-09-01T10:00:00Z")
        r      (gap/build-report [queued] {:harvest [(decision "2026-09-04" :admitted (:miss/id queued))]})]
    (is (= 0 (get-in r [:learning :decided])))
    (is (= [(:miss/id queued)] (get-in r [:learning :misses])))))

(deftest ^{:stratum 1} learning-counts-a-decided-miss-without-a-usable-timestamp
  (let [miss (assoc (miss-at :uncovered "unused") :miss/at #inst "2026-09-01")
        r    (gap/build-report [miss] {:harvest [(decision "2026-09-04" :admitted (:miss/id miss))]})]
    (is (= {:decided 1 :undated 1 :median-days nil :open 0}
           (select-keys (:learning r) [:decided :undated :median-days :open])))
    (is (re-find #"1 miss\(es\) decided, none with a usable timestamp" (gap/render-report r)))))

