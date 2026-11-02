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
(ns ai.miniforge.deliberation-harness.interface
  "Public API of the synthetic deliberation development harness (N15 §0.5):
   canonical digests, the hash-chained experiment journal, and the synthetic
   task package. Development comparisons are never N15 gate evidence."
  (:require
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.experiment :as experiment]
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [ai.miniforge.deliberation-harness.journal :as journal]
   [ai.miniforge.deliberation-harness.report :as report]
   [ai.miniforge.deliberation-harness.turn :as turn]))

;------------------------------------------------------------------------------ Layer 0

(def conditions
  "The development conditions; see `turn/conditions`."
  turn/conditions)

(defn digest
  "Lowercase hex SHA-256 of `value`'s canonical EDN; see `codec/digest`."
  [value]
  (codec/digest value))

(defn read-edn
  "Parse plain EDN text, or return an anomaly; see `codec/read-text`."
  [text]
  (codec/read-text text))

(defn open-journal!
  "Start a journal in a new directory; see `journal/open!`."
  [directory]
  (journal/open! directory))

(defn append!
  "Durably append one record; see `journal/append!`."
  [journal payload]
  (journal/append! journal payload))

(defn read-journal
  "Read and verify a journal; see `journal/read-journal`."
  [directory]
  (journal/read-journal directory))

(defn tasks
  "The synthetic task package; see `fixtures/tasks`."
  []
  (fixtures/tasks))

(defn score
  "Score a solution against a task's sealed reference; see `fixtures/score`."
  [task solution inspected]
  (fixtures/score task solution inspected))

(defn initial-workspace
  "The seed board for a task; see `turn/initial-workspace`."
  [task]
  (turn/initial-workspace task))

(defn run-cell
  "Run one development cell; see `experiment/run-cell`."
  [manifest task condition tier replicate call record!]
  (experiment/run-cell manifest task condition tier replicate call record!))

(defn render-report
  "Markdown summary of cell results; see `report/render`."
  [manifest results]
  (report/render manifest results))
