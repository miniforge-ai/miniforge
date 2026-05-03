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

(ns ai.miniforge.agent.tool-supervisor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.tool-supervisor :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Hook stdin parsing

(deftest read-hook-request-parses-single-json-value-without-eof-test
  (testing "hook request parsing succeeds from a live stream payload"
    (let [payload "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"x.clj\"}} trailing"
          result (binding [*in* (java.io.StringReader. payload)]
                   (#'sut/read-hook-request))]
      (is (= "Write" (:tool_name result)))
      (is (= "x.clj" (get-in result [:tool_input :file_path]))))))

(deftest hook-eval-stdin-reads-one-request-and-responds-test
  (testing "hook-eval responds without requiring stdin EOF"
    (let [payload "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}} trailing"
          output (with-out-str
                   (binding [*in* (java.io.StringReader. payload)]
                     (is (= 0 (sut/hook-eval-stdin!)))))]
      (is (= "{}\n" output)))))
