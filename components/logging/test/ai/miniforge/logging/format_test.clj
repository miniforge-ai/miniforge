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
(ns ai.miniforge.logging.format-test
  "The human format must carry an entry's :data, or payload-only events
   leave no evidence in console and file sinks."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.logging.format :as fmt]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} format-human-carries-data
  (testing "the data map is appended as readable EDN"
    (let [line (fmt/format-human {:log/level :info
                                  :log/category :implementer
                                  :log/event :implementer/prompt-sections
                                  :data {:sections [:gate-failures :phase-handoff]
                                         :prompt-chars 4120}})]
      (is (str/starts-with? line " [info] implementer/prompt-sections {"))
      (is (= {:sections [:gate-failures :phase-handoff] :prompt-chars 4120}
             (edn/read-string (subs line (str/index-of line "{")))))))
  (testing "message and data both render, message first"
    (is (= " [warn] policy/budget-exceeded - over {:used 100, :limit 50}"
           (fmt/format-human {:log/level :warn
                              :log/category :policy
                              :log/event :policy/budget-exceeded
                              :log/message "over"
                              :data {:used 100 :limit 50}}))))
  (testing "no data, no trailing map"
    (is (= " [info] loop/iteration-started"
           (fmt/format-human {:log/level :info
                              :log/category :loop
                              :log/event :loop/iteration-started})))))
