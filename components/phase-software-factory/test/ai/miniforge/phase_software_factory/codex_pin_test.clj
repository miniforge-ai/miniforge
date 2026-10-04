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
(ns ai.miniforge.phase-software-factory.codex-pin-test
  "The happy path (a real codex producing a pin) is covered by the codex
   component's own tests against generator-produced fixtures
   (ai.miniforge.codex.render-test/pin-entry-builds-a-prompt-ready-file);
   these cover the phase-side skip conditions."
  (:require [ai.miniforge.codex.interface :as codex]
   [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} no-configured-codex-means-no-pin-and-no-noise
  (is (nil? (codex-pin/pin-file :implement nil nil))))

(deftest ^{:stratum 0} unmapped-phase-gets-no-pin
  (is (nil? (codex-pin/pin-file :explore nil "/anywhere"))))

(deftest ^{:stratum 0} anomaly-skips-the-pin-rather-than-pinning-garbage
  (is (nil? (codex-pin/pin-file :implement nil "/nonexistent/codex"))))

(deftest ^{:stratum 0} only-wired-phases-are-mapped
  ;; implement/plan wire via pin-outcome (existing-files); review wires via
  ;; landings-text (prompt section); release wires via landings-outcome
  ;; through the releaser's behavior addendum. A mapping without a wire
  ;; would be a defined-but-unreachable capability.
  ;; verify is mapped for the gap instrument only (no agent, no pin) —
  ;; its misses classify against board 3 instead of :uncovered.
  (is (= #{:implement :plan :review :release :verify}
         (set (keys codex-pin/phase->situation))))
  (is (= {:implement ["submitting-work-to-enforced-gates"]}
         codex-pin/phase->secondary-situations)))

(deftest ^{:stratum 0} landings-text-skip-conditions
  (is (nil? (codex-pin/landings-text :review nil nil)))
  (is (nil? (codex-pin/landings-text :explore nil "/anywhere")))
  (is (nil? (codex-pin/landings-text :review nil "/nonexistent/codex"))))

(deftest ^{:stratum 0} anomaly-with-nil-logger-warns-on-stderr
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (nil? (codex-pin/pin-file :implement nil "/nonexistent/codex"))))
    (is (re-find #"WARN: codex consultation skipped for implement" (str err))
        "a nil logger must not turn a configured-codex failure silent")))

(deftest ^{:stratum 0} pin-outcome-states
  (is (= {:entry nil :status :unconfigured :anomaly nil
          :situation "changing-one-side-of-a-boundary" :pegs nil}
         (codex-pin/pin-outcome :implement nil nil)))
  (is (= {:entry nil :status :unmapped :anomaly nil :situation nil :pegs nil}
         (codex-pin/pin-outcome :explore nil "/anywhere")))
  (is (= :skipped
         (:status (codex-pin/pin-outcome :implement nil "/nonexistent/codex")))))

(deftest ^{:stratum 0} consultation-summary-distinguishes-unknown-from-unread
  (let [pinned {:entry {:path codex-pin/pin-path} :status :pinned :anomaly nil}]
    (testing "nil reads log means UNKNOWN, not false — absence of the record is not absence of the read"
      (is (nil? (:pin-read? (codex-pin/consultation-summary pinned nil)))))
    (testing "reads log without the pin path means false"
      (is (false? (:pin-read? (codex-pin/consultation-summary
                                pinned [{:path "src/a.clj" :source :cache}])))))
    (testing "reads log with the pin path means true"
      (is (true? (:pin-read? (codex-pin/consultation-summary
                               pinned [{:path codex-pin/pin-path :source :cache}])))))
    (testing "skipped consultation carries its anomaly"
      (is (= {:pinned? false :status :skipped :anomaly :codex-unreadable
              :situation nil :pegs nil :pin-read? nil}
             (dissoc (codex-pin/consultation-summary
                       {:entry nil :status :skipped :anomaly :codex-unreadable} nil)
                     :consultation-id))))))

(deftest ^{:stratum 0} consultation-summary-records-per-peg-telemetry
  ;; SPEC §7.7: per peg presented, which way it answered — push delivery
  ;; has no answer channel, so every presented peg records unanswered
  ;; (:answer nil) with the landing set behind each branch kept intact.
  (let [outcome {:entry {:path codex-pin/pin-path} :status :pinned
                 :anomaly nil :situation "process-stuck-or-slow"
                 :pegs [{:id "peg-a"
                         :answers {"yes" ["p1"] "no" ["p2" "p3"]}}]}]
    (is (= [{:id "peg-a" :answer nil
             :landings {"yes" ["p1"] "no" ["p2" "p3"]}}]
           (:pegs (codex-pin/consultation-summary outcome nil))))
    (testing "no pegs presented records nil, not an empty claim"
      (is (nil? (:pegs (codex-pin/consultation-summary
                         (assoc outcome :pegs []) nil)))))))

(deftest ^{:stratum 0} consultation-summary-fills-answers-from-the-recorded-log
  ;; SPEC §7.7.2: the session's answer_peg log fills :answer per presented
  ;; peg. Last recording wins (the agent's final position IS the
  ;; observation); a row matching no presented peg surfaces as
  ;; :unmatched-answers rather than vanishing.
  (let [outcome {:entry {:path codex-pin/pin-path} :status :pinned
                 :anomaly nil :situation "changing-one-side-of-a-boundary"
                 :pegs [{:id "peg-a" :answers {"yes" ["p1"] "no" ["p2"]}}
                        {:id "peg-b" :answers {"yes" ["p3"] "no" ["p4"]}}]}
        answers [{:peg-id "peg-a" :answer "no" :timestamp "t1"}
                 {:peg-id "peg-a" :answer "yes" :timestamp "t2"}
                 {:peg-id "ghost" :answer "no" :timestamp "t3"}]
        summary (codex-pin/consultation-summary outcome nil answers)]
    (is (= [{:id "peg-a" :answer "yes" :landings {"yes" ["p1"] "no" ["p2"]}}
            {:id "peg-b" :answer nil :landings {"yes" ["p3"] "no" ["p4"]}}]
           (:pegs summary))
        "answered peg takes the LAST recorded answer; unanswered stays nil")
    (is (= [{:peg-id "ghost" :answer "no" :timestamp "t3"}]
           (:unmatched-answers summary)))
    (testing "nil answer log = the 2-arity record, no :unmatched-answers key"
      (let [s2 (codex-pin/consultation-summary outcome nil)]
        (is (= [nil nil] (mapv :answer (:pegs s2))))
        (is (not (contains? s2 :unmatched-answers)))))))

(deftest ^{:stratum 0} attach-consultation-shapes
  (let [summary {:status :unconfigured :pinned? false}]
    (testing "failure result with nil :output gains the marker"
      (is (= summary
             (get-in (codex-pin/attach-consultation
                      {:status :error :error {:message "x"} :output nil} summary)
                     [:output :codex/consultation]))))
    (testing "scalar :output (specialized-agent failure path) is preserved
              under :agent/raw-output instead of crashing assoc-in"
      (let [r (codex-pin/attach-consultation
               {:status :error :output "raw agent text"} summary)]
        (is (= "raw agent text" (get-in r [:output :agent/raw-output])))
        (is (= summary (get-in r [:output :codex/consultation])))))
    (testing "map :output keeps its keys"
      (is (= {:code/summary "s" :codex/consultation summary}
             (:output (codex-pin/attach-consultation
                       {:status :success :output {:code/summary "s"}} summary)))))
    (testing "non-map result passes through untouched"
      (is (nil? (codex-pin/attach-consultation nil summary))))))

(deftest ^{:stratum 0} secondary-consultations-append-and-merge
  (let [primary {:path codex-pin/pin-path :content "PRIMARY" :pegs [{:id "p1"}]}]
    (testing "secondary landings append after a blank line; pegs merge primary-first"
      (with-redefs [codex/pin-entry (fn [_dir _situation _path] primary)
                    codex/consider (fn [_dir situation]
                                     (when (= "submitting-work-to-enforced-gates" situation)
                                       {:landings [] :pegs [{:id "s1"}]}))
                    codex/render-response (fn [_resp] "SECONDARY")]
        (let [out (codex-pin/pin-outcome :implement nil "/codex")]
          (is (= :pinned (:status out)))
          (is (= "PRIMARY\n\nSECONDARY" (get-in out [:entry :content])))
          (is (= [{:id "p1"} {:id "s1"}] (:pegs out)))
          (is (= "changing-one-side-of-a-boundary" (:situation out))
              "the ledger's situation stays the primary"))))
    (testing "a secondary that fails to answer warns and is dropped; the primary stands"
      (let [err (java.io.StringWriter.)]
        (with-redefs [codex/pin-entry (fn [_dir _situation _path] primary)
                      codex/consider (fn [_dir _situation]
                                       {:codex/anomaly :codex-unreadable :codex/reason "no nodes"})
                      codex/render-response (fn [_resp] (throw (ex-info "must not render an anomaly" {})))]
          (let [out (binding [*err* err] (codex-pin/pin-outcome :implement nil "/codex"))]
            (is (= :pinned (:status out)))
            (is (= "PRIMARY" (get-in out [:entry :content])))
            (is (= [{:id "p1"}] (:pegs out)))
            (is (re-find #"WARN: codex consultation skipped for implement" (str err)))))))))

(deftest ^{:stratum 0} consultation-summary-dedupes-identical-peg-rows
  ;; SPEC §7.7.2: pin-outcome concatenates primary and secondary
  ;; consultations without dedup; a peg both reach must not record its
  ;; one answer as two observations. Rows with different landings are
  ;; different presentations and both stay.
  (let [peg {:id "peg-a" :answers {"yes" ["p1"] "no" ["p2"]}}
        other {:id "peg-a" :answers {"yes" ["p9"] "no" ["p2"]}}
        outcome {:entry {:path codex-pin/pin-path} :status :pinned
                 :anomaly nil :situation "s"
                 :pegs [peg peg other]}
        summary (codex-pin/consultation-summary
                 outcome nil [{:peg-id "peg-a" :answer "yes" :timestamp "t"}])]
    (is (= [{:id "peg-a" :answer "yes" :landings {"yes" ["p1"] "no" ["p2"]}}
            {:id "peg-a" :answer "yes" :landings {"yes" ["p9"] "no" ["p2"]}}]
           (:pegs summary))
        "identical rows collapse; a different landing snapshot survives")))

(deftest ^{:stratum 0} consultation-summary-stamps-one-identity-per-construction
  ;; SPEC §7.7.2.1: every miss entry of a leave and its consultation
  ;; record share the summary's identity; two constructions (a retry
  ;; attempt) are two consultations.
  (let [outcome {:entry {:path codex-pin/pin-path} :status :pinned
                 :anomaly nil :situation "s" :pegs []}
        s1 (codex-pin/consultation-summary outcome nil)
        s2 (codex-pin/consultation-summary outcome nil)]
    (is (uuid? (:consultation-id s1)))
    (is (not= (:consultation-id s1) (:consultation-id s2)))))
