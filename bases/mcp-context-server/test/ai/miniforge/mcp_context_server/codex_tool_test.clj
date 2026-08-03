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
  "Rendering itself is pinned by the codex component's tests
   (ai.miniforge.codex.render-test) — these cover the tool's fail-closed
   paths and param handling only."
  (:require [ai.miniforge.mcp-context-server.codex-tool :as codex-tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} text-of [result]
  (-> result :content first :text))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unconfigured-codex-fails-closed
  ;; no codex_path param; MINIFORGE_CODEX_PATH is not set in the test env
  (when-not (System/getenv "MINIFORGE_CODEX_PATH")
    (let [text (text-of (codex-tool/handle-consider-situation
                          {"situation" "an agent needs to act"}))]
      (is (str/includes? text "codex anomaly: codex-unconfigured"))
      (is (str/includes? text "MINIFORGE_CODEX_PATH")))))

(deftest ^{:stratum 1} non-string-codex-path-is-treated-as-absent
  (when-not (System/getenv "MINIFORGE_CODEX_PATH")
    (let [text (text-of (codex-tool/handle-consider-situation
                          {"situation" "anything" "codex_path" 42}))]
      (is (str/includes? text "codex anomaly: codex-unconfigured")))))

(deftest ^{:stratum 1} unreadable-codex-is-an-anomaly-not-an-empty-answer
  (let [text (text-of (codex-tool/handle-consider-situation
                        {"situation" "anything"
                         "codex_path" "/nonexistent/codex"}))]
    (is (str/includes? text "codex anomaly: codex-unreadable"))))
