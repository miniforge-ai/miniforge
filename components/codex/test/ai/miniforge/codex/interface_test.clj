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
(ns ai.miniforge.codex.interface-test
  "Fixtures are VERBATIM copies of files produced by the codex generators
   (build-board-stuck-or-slow.py and friends) — the real producer's output
   shape, never hand-invented. If the generators change their emit format,
   refresh the fixture files from a regenerated codex rather than editing
   them here."
  (:require [ai.miniforge.codex.interface :as codex]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fixture-dir
  (-> (io/resource "codex-fixture/nodes") io/file .getParentFile .getPath))

(deftest ^{:stratum 0} load-graph-anomaly-on-missing-dir
  (let [graph (codex/load-graph "/nonexistent/codex/path")]
    (is (= :codex-unreadable (:codex/anomaly graph)))))

(def ^{:stratum 0} codex-horizon-rank {"strategic" 0 "operational" 1 "tactical" 2})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} load-graph-reads-generated-nodes
  (let [{:keys [nodes] :as graph} (codex/load-graph fixture-dir)]
    (is (nil? (:codex/anomaly graph)))
    (is (= 21 (count nodes)))
    (testing "frontmatter fields survive parsing"
      (let [peg (get nodes "consuming-an-earlier-steps-output")]
        (is (= "discriminator" (:type peg)))
        (is (= "owned" (:origin peg)))
        (is (= "21" (:support peg)))
        (is (= "tactical" (:horizon peg)))
        (is (= [{:answer "yes" :target "contract-drift-is-silent"}
                {:answer "no" :target "started-in-the-intended-environment"}]
               (:routes-to peg)))))))

(deftest ^{:stratum 1} consider-routes-a-situation-to-landings
  (let [{:keys [situation landings coverage] :as resp}
        (codex/consider fixture-dir "process-stuck-or-slow")]
    (is (nil? (:codex/anomaly resp)))
    (is (= "process-stuck-or-slow" situation))
    (testing "every problem on the board's playable paths lands"
      (is (= #{"capacity-headroom" "liveness-detection" "infra-vs-domain-failure"
               "environment-isolation" "contract-drift-is-silent"
               "chained-work-identity" "fail-closed-by-default"}
             (set (map :id landings)))))
    (testing "coverage statement is present and honest (SPEC §7.5, §7.6.2)"
      (is (= 7 (:landing-count coverage)))
      (is (= 1 (:unanchored-count coverage)))   ; contract-drift-is-silent
      (is (true? (:no-strategic-coverage? coverage)))
      (is (= {"operational" 6 "tactical" 1} (:horizon-mix coverage)))
      (is (= "2026-06-07" (:newest-scar-date coverage))))
    (testing "coverage confesses the §4.4 trigger has no data yet (§7.7)"
      (is (= :untriggerable (:retirement coverage))))))

(deftest ^{:stratum 1} consider-carries-the-per-peg-telemetry-basis
  (let [{:keys [pegs]} (codex/consider fixture-dir "process-stuck-or-slow")]
    (testing "every discriminator on the board is a presented peg (§7.7)"
      (is (= ["already-failed-silently" "consuming-an-earlier-steps-output"
              "is-there-progress-at-all" "reports-activity-while-stuck"
              "started-in-the-intended-environment"]
             (mapv :id pegs))))
    (testing "each answer carries the landing set that follows it — exposes edges included (§4.4.1 routing relevance)"
      (is (= {"yes" ["fail-closed-by-default" "infra-vs-domain-failure"]
              "no" ["chained-work-identity" "contract-drift-is-silent"
                    "environment-isolation" "liveness-detection"]}
             (:answers (first (filter #(= "already-failed-silently" (:id %))
                                      pegs))))))
    (testing "a leaf peg's branches are single landings"
      (is (= {"no" ["environment-isolation"] "yes" ["liveness-detection"]}
             (:answers (first (filter #(= "started-in-the-intended-environment"
                                          (:id %))
                                      pegs))))))))

(deftest ^{:stratum 1} landings-are-horizon-ordered
  (let [{:keys [landings]} (codex/consider fixture-dir "process-stuck-or-slow")
        ranks (mapv #(get codex-horizon-rank (:horizon %)) landings)]
    (is (= ranks (vec (sort ranks)))
        "strategic → operational → tactical, §7.6.1")))

(deftest ^{:stratum 1} escalations-are-flagged
  (let [{:keys [landings]} (codex/consider fixture-dir "process-stuck-or-slow")
        fail-closed (first (filter #(= "fail-closed-by-default" (:id %)) landings))]
    (testing "operational problem with a strategic-cost scar (§7.6.3)"
      (is (= ["autodev-governance-leak"] (:escalations fail-closed))))))

(deftest ^{:stratum 1} substring-matches-a-situation
  (let [resp (codex/consider fixture-dir "stuck")]
    (is (= "process-stuck-or-slow" (:situation resp)))))

(deftest ^{:stratum 1} exact-id-match-is-case-insensitive
  (let [resp (codex/consider fixture-dir "Process-Stuck-Or-Slow")]
    (is (= "process-stuck-or-slow" (:situation resp)))))

(deftest ^{:stratum 1} unmatched-situation-is-an-answer-not-an-empty-list
  (let [resp (codex/consider fixture-dir "planning a birthday party")]
    (is (= :situation-unmatched (:codex/anomaly resp)))
    (is (= [["process-stuck-or-slow" "A long-running process is stuck or slow"]]
           (:codex/available resp)))))

(deftest ^{:stratum 1} blank-situation-is-a-programmer-error
  (is (thrown? IllegalArgumentException
               (codex/consider fixture-dir "  "))))
