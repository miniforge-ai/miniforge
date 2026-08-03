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
(ns ai.miniforge.mcp-context-server.codex-tool-test
  "The response-map shapes rendered here are pinned by the codex component's
   own tests (ai.miniforge.codex.interface-test) against generator-produced
   fixtures; these tests cover the base's rendering and fail-closed paths."
  (:require [ai.miniforge.mcp-context-server.codex-tool :as codex-tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} text-of [result]
  (-> result :content first :text))

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
        text (codex-tool/render-response resp)]
    (testing "strategic renders before operational (§7.6.1)"
      (is (< (str/index-of text "p-strategic") (str/index-of text "p-op"))))
    (testing "escalation and unanchored are flagged inline (§7.6.3)"
      (is (str/includes? text "ESCALATION: scar-1"))
      (is (str/includes? text "unanchored — reasoned, not survived")))
    (testing "coverage line leads with counts (§7.5)"
      (is (str/includes? text "coverage: 2 landings, 1 unanchored")))))

(deftest ^{:stratum 0} render-says-when-strategic-coverage-is-absent
  (let [resp {:situation "s1" :situation-title "t"
              :landings [{:id "p" :type "problem" :title "t" :horizon "tactical"
                          :confidence "high" :open [] :scars [] :escalations []}]
              :coverage {:landing-count 1 :unanchored-count 1
                         :horizon-mix {"tactical" 1}
                         :no-strategic-coverage? true
                         :newest-scar-date nil}}
        text (codex-tool/render-response resp)]
    (is (str/includes? text "NO STRATEGIC COVERAGE"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unconfigured-codex-fails-closed
  ;; no codex_path param; MINIFORGE_CODEX_PATH is not set in the test env
  (when-not (System/getenv "MINIFORGE_CODEX_PATH")
    (let [text (text-of (codex-tool/handle-consider-situation
                          {"situation" "an agent needs to act"}))]
      (is (str/includes? text "codex anomaly: codex-unconfigured"))
      (is (str/includes? text "MINIFORGE_CODEX_PATH")))))

(deftest ^{:stratum 1} unreadable-codex-is-an-anomaly-not-an-empty-answer
  (let [text (text-of (codex-tool/handle-consider-situation
                        {"situation" "anything"
                         "codex_path" "/nonexistent/codex"}))]
    (is (str/includes? text "codex anomaly: codex-unreadable"))))
