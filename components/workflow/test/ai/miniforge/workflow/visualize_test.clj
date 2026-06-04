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

(ns ai.miniforge.workflow.visualize-test
  "Phase 6 of the FSM RFC: tests for the Mermaid printer. Light
   coverage — the printer is structurally simple. Goal here is to pin
   that the output contains the right shape so a docs/IDE renderer
   shows the verdict-driven guarded array correctly."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.fsm :as fsm]
   [ai.miniforge.workflow.visualize :as visualize]))

(defn- production-workflow []
  {:workflow/id :phase6-mermaid-test
   :workflow/pipeline [{:phase :plan}
                       {:phase :implement :on-fail :plan}
                       {:phase :verify    :on-fail :implement}
                       {:phase :review    :on-fail :implement}
                       {:phase :release   :on-fail :implement}
                       {:phase :done}]})

(deftest emits-stateDiagram-v2-header-test
  (testing "the output is a Mermaid stateDiagram-v2 block — the first
            line names the diagram kind so any Markdown renderer
            recognizes it"
    (let [machine (fsm/compile-execution-machine (production-workflow))
          out     (visualize/machine->mermaid machine)]
      (is (str/starts-with? out "stateDiagram-v2"))
      (is (str/includes? out "[*] --> pending")
          "initial pseudo-state arrow points at :pending"))))

(deftest emits-guarded-fail-array-branches-as-separate-edges-test
  (testing "each guarded :phase/fail branch becomes its own edge with
            its guard / action keyword in the label — the whole point
            of the Phase 6 export, since pre-Phase-2b machines had a
            flat :phase/fail and the verdict-driven shape needs visual
            audit"
    (let [machine (fsm/compile-execution-machine (production-workflow))
          out     (visualize/machine->mermaid machine)]
      (is (str/includes? out ":phase/fail [:verdict/terminal?]")
          "terminal-verdict guard edge present")
      (is (str/includes? out ":phase/fail [:budget/redirects-spent?]")
          "budget-spent guard edge present")
      (is (str/includes? out "{:redirect/inc-count}")
          "redirect counter action surfaces in its edge label"))))

(deftest emits-terminal-state-arrows-test
  (testing "final states (:completed, :failed, :cancelled) get an arrow
            out to [*] so the renderer marks them as terminal"
    (let [machine (fsm/compile-execution-machine (production-workflow))
          out     (visualize/machine->mermaid machine)]
      (is (str/includes? out "completed --> [*]"))
      (is (str/includes? out "failed --> [*]"))
      (is (str/includes? out "cancelled --> [*]")))))

(deftest tolerates-plain-flat-transitions-test
  (testing "non-guarded phases (Phase 4 didn't migrate :plan or :done)
            still produce single edges for :phase/fail rather than
            multiple branches — bare-keyword transition shape"
    (let [;; Trim down to a workflow with only non-guarded phases so the
          ;; guarded-array branches don't dominate the output.
          machine (fsm/compile-execution-machine
                    {:workflow/id :plain
                     :workflow/pipeline [{:phase :plan}
                                         {:phase :done}]})
          out     (visualize/machine->mermaid machine)]
      (is (str/includes? out "plan --> ")
          ":phase-0-plan name is sanitized to 'plan' family")
      ;; The guarded-array-only token must NOT appear for plain phases
      (is (not (str/includes? out "{:redirect/inc-count}"))
          "non-guarded phase machines emit no redirect-counting edge"))))
