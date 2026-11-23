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
(ns ai.miniforge.deliberation-harness.turn-test
  (:require
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [ai.miniforge.deliberation-harness.turn :as turn]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} the-model-view-keeps-answers-and-replacements
  (let [board (-> (turn/initial-workspace (first (fixtures/tasks)))
                  (assoc-in [:workspace/objects turn/goal-id :object/answer] "answered"))
        seen (first (filter #(= turn/goal-id (:object/id %))
                            (:objects (turn/model-view board :proposer))))]
    (is (= "answered" (:object/answer seen)))))
