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
(ns ai.miniforge.agent.implementer-gate-evidence-test
  "The gate-denial section names the stale files and their matching
   lines from the error map itself -- the message text is not the only
   carrier."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} denial-section-carries-files-and-hit-lines
  (testing "trap-bench shape: the catalog key was unresolved, so :message
            is the bare word; :files and :hits still reach the prompt"
    (let [text (agent/implementer-task->text
                {:task/description "rename the key"
                 :task/gate-failures
                 [{:gate :stale-references
                   :errors [{:type :stale-reference
                             :token ":skipped"
                             :message "stale"
                             :files ["bb.edn"]
                             :hits [{:file "bb.edn" :line 596
                                     :text ":task (:skipped (codex-gap/read-ledger d))"}]}]}]})]
      (is (str/includes? text "- [stale-references] stale"))
      (is (str/includes? text "bb.edn"))
      (is (str/includes? text "bb.edn:596: :task (:skipped (codex-gap/read-ledger d))")))))
