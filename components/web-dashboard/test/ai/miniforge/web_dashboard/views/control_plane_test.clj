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

(ns ai.miniforge.web-dashboard.views.control-plane-test
  "Tests for the Control Plane view — agent cards, decision queue,
   summary bar, and full page composition."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.web-dashboard.views.control-plane :as sut]
   [hiccup2.core :refer [html]]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Test fixtures / helpers
;; ---------------------------------------------------------------------------

(defn- make-agent
  "Build a minimal agent record, merging overrides."
  [overrides]
  (merge {:agent/id      (random-uuid)
          :agent/vendor  :claude-code
          :agent/name    "Test Agent"
          :agent/status  :running
          :agent/task    "Reviewing PR #42"
          :agent/last-heartbeat (java.util.Date.)
          :agent/registered-at  (java.util.Date.)
          :agent/heartbeat-interval-ms 30000
          :agent/capabilities #{:code-review}
          :agent/tags #{:backend}
          :agent/metadata {:session-id "abc-123"}}
         overrides))

(defn- make-decision
  "Build a minimal decision record, merging overrides."
  [overrides]
  (merge {:decision/id         (random-uuid)
          :decision/priority   :medium
          :decision/type       :choice
          :decision/summary    "Pick a strategy"
          :decision/created-at (java.util.Date.)
          :decision/options    ["Option A" "Option B"]}
         overrides))

(defn- render-str
  "Render a hiccup form to a string via hiccup2."
  [form]
  (str (html form)))

(defn- contains-substr?
  "True when haystack contains needle (case-sensitive)."
  [haystack needle]
  (str/includes? (str haystack) needle))

;; ---------------------------------------------------------------------------
;; Layer 0 — status-class (private, tested via agent-card output)
;; ---------------------------------------------------------------------------

(deftest status-class-mapping-via-agent-card
  (testing "Each known status produces its CSS class in the rendered card"
    (doseq [status [:running :idle :blocked :paused :completed
                    :failed :unreachable :terminated :initializing]]
      (let [agent (make-agent {:agent/status status})
            out   (render-str (sut/agent-card agent))]
        (is (contains-substr? out (str "status-" (name status)))
            (str "Expected status-" (name status) " in output"))))))

(deftest unknown-status-gets-unknown-class
  (testing "An unrecognised status keyword falls through to status-unknown"
    (let [agent (make-agent {:agent/status :weird})
          out   (render-str (sut/agent-card agent))]
      (is (contains-substr? out "status-unknown")))))

;; ---------------------------------------------------------------------------
;; Layer 0 — vendor-icon / vendor-label (tested via card & detail output)
;; ---------------------------------------------------------------------------

(deftest vendor-icons-in-agent-card
  (testing "Known vendors render their short icon text"
    (are [vendor icon]
      (contains-substr? (render-str (sut/agent-card (make-agent {:agent/vendor vendor}))) icon)
      :claude-code "C"
      :miniforge   "M"
      :openai      "O"
      :cursor      "Cu"
      :ollama      "L"))
  (testing "Unknown vendor renders ?"
    (is (contains-substr?
         (render-str (sut/agent-card (make-agent {:agent/vendor :unknown-vendor})))
         "?"))))

;; ---------------------------------------------------------------------------
;; Layer 0 — agent-card
;; ---------------------------------------------------------------------------

(deftest agent-card-basic-structure
  (let [id    (random-uuid)
        agent (make-agent {:agent/id id :agent/name "Alpha" :agent/status :running})
        out   (render-str (sut/agent-card agent))]
    (testing "Contains agent name"
      (is (contains-substr? out "Alpha")))
    (testing "Contains data-agent-id attribute"
      (is (contains-substr? out (str id))))
    (testing "Contains status class"
      (is (contains-substr? out "status-running")))))

(deftest agent-card-unnamed-agent
  (testing "nil :agent/name falls back to 'Unnamed Agent'"
    (let [out (render-str (sut/agent-card (make-agent {:agent/name nil})))]
      (is (contains-substr? out "Unnamed Agent")))))

(deftest agent-card-pause-button-shown-for-running-and-idle
  (doseq [status [:running :idle]]
    (let [out (render-str (sut/agent-card (make-agent {:agent/status status})))]
      (is (contains-substr? out "Pause")
          (str "Pause should appear for " (name status))))))

(deftest agent-card-resume-button-shown-only-for-paused
  (let [out-paused (render-str (sut/agent-card (make-agent {:agent/status :paused})))
        out-running (render-str (sut/agent-card (make-agent {:agent/status :running})))]
    (is (contains-substr? out-paused "Resume"))
    (is (not (contains-substr? out-running "Resume")))))

(deftest agent-card-kill-button-absent-for-terminal-states
  (doseq [status [:completed :failed :terminated]]
    (let [out (render-str (sut/agent-card (make-agent {:agent/status status})))]
      (is (not (contains-substr? out "Kill"))
          (str "Kill should NOT appear for " (name status))))))

(deftest agent-card-kill-button-present-for-non-terminal-states
  (doseq [status [:running :idle :blocked :paused :unreachable :initializing]]
    (let [out (render-str (sut/agent-card (make-agent {:agent/status status})))]
      (is (contains-substr? out "Kill")
          (str "Kill should appear for " (name status))))))

(deftest agent-card-task-shown-when-present
  (let [out (render-str (sut/agent-card (make-agent {:agent/task "Build the widget"})))]
    (is (contains-substr? out "Build the widget"))))

(deftest agent-card-task-absent-when-nil
  (let [out (render-str (sut/agent-card (make-agent {:agent/task nil})))]
    (is (not (contains-substr? out "Task:")))))

(deftest agent-card-heartbeat-never-when-nil
  (let [out (render-str (sut/agent-card (make-agent {:agent/last-heartbeat nil})))]
    (is (contains-substr? out "never"))))

;; ---------------------------------------------------------------------------
;; agents-grid-fragment
;; ---------------------------------------------------------------------------

(deftest agents-grid-fragment-empty
  (let [out (str (sut/agents-grid-fragment []))]
    (testing "Empty state shows robot emoji and register hint"
      (is (contains-substr? out "No Agents Registered"))
      (is (contains-substr? out "POST /api/control-plane/agents/register")))))

(deftest agents-grid-fragment-renders-all-agents
  (let [agents [(make-agent {:agent/name "A1"})
                (make-agent {:agent/name "A2"})]
        out    (str (sut/agents-grid-fragment agents))]
    (is (contains-substr? out "A1"))
    (is (contains-substr? out "A2"))))

(deftest agents-grid-fragment-sorts-blocked-first
  (testing "Blocked agents sort before running agents"
    (let [blocked (make-agent {:agent/name "ZZZ-Blocked" :agent/status :blocked})
          running (make-agent {:agent/name "AAA-Running" :agent/status :running})
          out     (str (sut/agents-grid-fragment [running blocked]))
          idx-b   (str/index-of out "ZZZ-Blocked")
          idx-r   (str/index-of out "AAA-Running")]
      (is (< idx-b idx-r)
          "Blocked agent should appear before running agent"))))

;; ---------------------------------------------------------------------------
;; Layer 1 — decision-item / decision-queue-fragment
;; ---------------------------------------------------------------------------

(deftest decision-item-renders-priority-badge
  (doseq [priority [:critical :high :medium :low]]
    (let [d   (make-decision {:decision/priority priority})
          out (render-str (sut/decision-item d))]
      (is (contains-substr? out (str/upper-case (name priority)))
          (str "Should show " (name priority) " badge")))))

(deftest decision-item-renders-summary
  (let [out (render-str (sut/decision-item (make-decision {:decision/summary "Choose approach"})))]
    (is (contains-substr? out "Choose approach"))))

(deftest decision-item-structured-options
  (let [d   (make-decision {:decision/options ["Yes" "No"]})
        out (render-str (sut/decision-item d))]
    (testing "Buttons rendered for each option"
      (is (contains-substr? out "Yes"))
      (is (contains-substr? out "No")))))

(deftest decision-item-free-form-when-no-options
  (let [d   (make-decision {:decision/options nil})
        out (render-str (sut/decision-item d))]
    (testing "Shows free-form input when no options"
      (is (contains-substr? out "Type your response"))
      (is (contains-substr? out "Send")))))

(deftest decision-item-context-shown-when-present
  (let [d   (make-decision {:decision/context "Some extra context"})
        out (render-str (sut/decision-item d))]
    (is (contains-substr? out "Some extra context"))))

(deftest decision-item-context-absent-when-nil
  (let [d   (make-decision {:decision/context nil})
        out (render-str (sut/decision-item d))]
    (is (not (contains-substr? out "cp-decision-context")))))

(deftest decision-queue-fragment-empty
  (let [out (str (sut/decision-queue-fragment []))]
    (is (contains-substr? out "No decisions pending"))))

(deftest decision-queue-fragment-renders-items
  (let [decisions [(make-decision {:decision/summary "D1"})
                   (make-decision {:decision/summary "D2"})]
        out       (str (sut/decision-queue-fragment decisions))]
    (is (contains-substr? out "D1"))
    (is (contains-substr? out "D2"))))

;; ---------------------------------------------------------------------------
;; Layer 2 — summary-bar
;; ---------------------------------------------------------------------------

(deftest summary-bar-renders-stats
  (let [stats {:total-agents 10
               :by-status {:running 5 :blocked 2 :unreachable 1 :idle 2}
               :pending-decisions 3}
        out   (render-str (sut/summary-bar stats))]
    (testing "Total agents"
      (is (contains-substr? out "10")))
    (testing "Running count"
      (is (contains-substr? out "5")))
    (testing "Need Attention sums blocked + unreachable"
      (is (contains-substr? out "3"))) ;; 2 + 1
    (testing "Pending decisions"
      ;; 3 pending decisions
      (is (contains-substr? out "Decisions")))))

(deftest summary-bar-zero-defaults
  (let [stats {:total-agents 0 :by-status {} :pending-decisions 0}
        out   (render-str (sut/summary-bar stats))]
    (is (contains-substr? out "0"))))

;; ---------------------------------------------------------------------------
;; Layer 2 — control-plane-content (full page)
;; ---------------------------------------------------------------------------

(deftest control-plane-content-structure
  (let [agents    [(make-agent {:agent/name "Agent-X"})]
        decisions [(make-decision {:decision/summary "Pick one"})]
        stats     {:total-agents 1 :by-status {:running 1} :pending-decisions 1}
        out       (render-str (sut/control-plane-content agents decisions stats))]
    (testing "Page header"
      (is (contains-substr? out "Agent Control Plane")))
    (testing "Summary bar present"
      (is (contains-substr? out "cp-summary-bar")))
    (testing "Agents grid present with htmx polling"
      (is (contains-substr? out "cp-agents-grid"))
      (is (contains-substr? out "every 5s")))
    (testing "Decision queue present with htmx polling"
      (is (contains-substr? out "cp-decision-queue"))
      (is (contains-substr? out "every 3s")))
    (testing "Agent rendered in grid"
      (is (contains-substr? out "Agent-X")))
    (testing "Decision rendered in queue"
      (is (contains-substr? out "Pick one")))))

(deftest control-plane-content-empty-state
  (let [out (render-str (sut/control-plane-content [] [] {:total-agents 0 :by-status {} :pending-decisions 0}))]
    (is (contains-substr? out "No Agents Registered"))
    (is (contains-substr? out "No decisions pending"))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest agent-card-with-minimal-record
  (testing "Card renders with only required keys (id, status, vendor)"
    (let [agent {:agent/id (random-uuid)
                 :agent/status :idle
                 :agent/vendor :miniforge}
          out   (render-str (sut/agent-card agent))]
      (is (contains-substr? out "Unnamed Agent"))
      (is (contains-substr? out "idle"))
      (is (contains-substr? out "M")))))

(deftest decision-item-unknown-priority-falls-to-medium
  (let [d   (make-decision {:decision/priority :unknown})
        out (render-str (sut/decision-item d))]
    (is (contains-substr? out "priority-medium"))))

(deftest decision-item-nil-type-defaults-to-choice
  (let [d   (make-decision {:decision/type nil})
        out (render-str (sut/decision-item d))]
    (is (contains-substr? out "choice"))))
