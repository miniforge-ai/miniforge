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
(ns ai.miniforge.cli.workflow-runner.provenance-test
  "The run-start banner says when the agent runs unisolated (local mode)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.provenance :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} banner
  [quiet context]
  (with-out-str (sut/print-runtime-provenance! quiet (merge {:source-root "/src" :worktree-path "/wt"} context))))

(def ^{:stratum 0} notice (messages/t :workflow-runner/local-mode-notice))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} local-mode-notice-test
  (testing "local mode, given or by default, prints one line saying the agent is unisolated"
    (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote notice))
                            (banner false {:execution-mode :local})))))
    (is (str/includes? (banner false {}) notice)))
  (testing "the line names the privileges and the isolated mode"
    (is (str/includes? notice "privileges"))
    (is (str/includes? notice "--execution-mode governed")))
  (testing "governed mode, or --quiet: no notice"
    (is (not (str/includes? (banner false {:execution-mode :governed}) notice)))
    (is (= "" (banner true {:execution-mode :local})))))
