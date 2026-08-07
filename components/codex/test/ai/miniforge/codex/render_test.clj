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
(ns ai.miniforge.codex.render-test
  "The response-map shapes here are pinned by interface-test against
   generator-produced fixtures; these tests cover the text rendering rules
   (SPEC §7.5, §7.6)."
  (:require [ai.miniforge.codex.interface :as codex]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} render-orders-and-flags
  (let [resp {:situation "s1"
              :situation-title "A situation"
              :landings [{:id "p-strategic" :type "problem" :title "Big one"
                          :horizon "strategic" :confidence "high"
                          :open [] :scars [] :escalations []}
                         {:id "p-op" :type "problem" :title "Runs the system"
                          :horizon "operational" :confidence "high"
                          :open ["still open"]
                          :scars [{:id "scar-1" :date "2026-01" :cost "an outage"
                                   :cost-horizon "strategic" :origin "owned"}]
                          :escalations ["scar-1"]}]
              :coverage {:landing-count 2 :unanchored-count 1
                         :horizon-mix {"strategic" 1 "operational" 1}
                         :no-strategic-coverage? false
                         :newest-scar-date "2026-01"}}
        text (codex/render-response resp)]
    (testing "strategic renders before operational (§7.6.1)"
      (is (< (str/index-of text "p-strategic") (str/index-of text "p-op"))))
    (testing "escalation and unanchored are flagged inline (§7.6.3)"
      (is (str/includes? text "ESCALATION: scar-1"))
      (is (str/includes? text "unanchored — reasoned, not survived")))
    (testing "coverage line leads with counts and §7.6.1-ordered mix (§7.5)"
      (is (str/includes? text "coverage: 2 landings, 1 unanchored"))
      (is (str/includes? text "strategic 1 · operational 1")))))

(deftest ^{:stratum 0} render-says-when-strategic-coverage-is-absent
  (let [resp {:situation "s1" :situation-title "t"
              :landings [{:id "p" :type "problem" :title "t" :horizon "tactical"
                          :confidence "high" :open [] :scars [] :escalations []}]
              :coverage {:landing-count 1 :unanchored-count 1
                         :horizon-mix {"tactical" 1}
                         :no-strategic-coverage? true
                         :newest-scar-date nil}}
        text (codex/render-response resp)]
    (is (str/includes? text "NO STRATEGIC COVERAGE"))))

(deftest ^{:stratum 0} pin-entry-builds-a-prompt-ready-file
  (let [fixture-dir (-> (io/resource "codex-fixture/nodes")
                        io/file .getParentFile .getPath)
        entry (codex/pin-entry fixture-dir "process-stuck-or-slow"
                               ".miniforge/codex-consider.md")]
    (is (= ".miniforge/codex-consider.md" (:path entry)))
    (is (str/includes? (:content entry) "Pinned at phase start"))
    (is (str/includes? (:content entry) "situation: process-stuck-or-slow"))
    (is (str/includes? (:content entry) "coverage:"))))

(deftest ^{:stratum 0} pin-entry-surfaces-anomalies-instead-of-pinning-them
  (let [entry (codex/pin-entry "/nonexistent/codex" "anything" "x.md")]
    (is (= :codex-unreadable (:codex/anomaly entry)))
    (is (nil? (:path entry)))))
