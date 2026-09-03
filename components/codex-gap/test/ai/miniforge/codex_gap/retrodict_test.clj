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
(ns ai.miniforge.codex-gap.retrodict-test
  "Retrodiction remaps a recorded miss's situation and bucket while
   preserving the recorded bucket — the pure part, tested with an
   injected classifier."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.codex-gap.retrodict :as retrodict]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} retrodict-entry-remaps-situation-and-preserves-recorded-bucket
  (let [entry {:miss/id :m1 :miss/phase :verify :miss/situation nil
               :miss/bucket :uncovered :miss/consultation nil
               :miss/signal {:type :gate-failure :payload {:reason/rule-id :std/config-as-data}}}
        classify-fn (fn [e resp _problems _opts]
                      {:bucket (if (and (:miss/situation e) resp) :unheeded :uncovered)
                       :attribution (when resp {:problem :enforcement-after-authoring
                                                :method :mechanical :confidence :high})})
        consider (fn [situation] (when situation {:landings [:enforcement-after-authoring]}))]
    (testing "a supplied situation moves an :uncovered miss into a covered bucket"
      (let [out (retrodict/retrodict-entry classify-fn consider [] {} "submitting-work-to-enforced-gates" entry)]
        (is (= :uncovered (:miss/bucket-recorded out)) "the recorded bucket is preserved")
        (is (= :unheeded (:miss/bucket out)))
        (is (= "submitting-work-to-enforced-gates" (:miss/situation out)))
        (is (true? (:miss/retrodicted? out)))))
    (testing "retrodicting twice preserves the ORIGINAL recorded bucket"
      (let [once (retrodict/retrodict-entry classify-fn consider [] {} "submitting-work-to-enforced-gates" entry)
            twice (retrodict/retrodict-entry classify-fn consider [] {} "submitting-work-to-enforced-gates" once)]
        (is (= :uncovered (:miss/bucket-recorded twice)))
        (is (= :unheeded (:miss/bucket twice)))))
    (testing "a BLANK mapping behaves like no mapping — never reaches consider"
      (let [exploding (fn [_] (throw (ex-info "consider called with blank" {})))
            out (retrodict/retrodict-entry classify-fn exploding [] {} "  " entry)]
        (is (= :uncovered (:miss/bucket out)))
        (is (nil? (:miss/situation out)))))
    (testing "no mapping keeps the recorded situation (nil) and bucket"
      (let [out (retrodict/retrodict-entry classify-fn consider [] {} nil entry)]
        (is (= :uncovered (:miss/bucket out)))
        (is (nil? (:miss/situation out)))))))
