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

(ns ai.miniforge.web-dashboard.views.control-plane
  "Control Plane view — unified agent management dashboard.

   Shows all registered agents across vendors with status cards,
   a prioritized decision queue, and inline decision resolution.

   Layer 0: Agent card fragments
   Layer 1: Decision queue fragments
   Layer 2: Full page view"
  (:require
   [hiccup2.core :refer [html]]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Status helpers

(defn- status-class
  "CSS class for agent status badge."
  [status]
  (case status
    :running      "status-running"
    :idle         "status-idle"
    :blocked      "status-blocked"
    :paused       "status-paused"
    :completed    "status-completed"
    :failed       "status-failed"
    :unreachable  "status-unreachable"
    :terminated   "status-terminated"
    :initializing "status-initializing"
    "status-unknown"))

(defn- vendor-icon
  "Display icon for vendor."
  [vendor]
  (case vendor
    :claude-code "C"
    :miniforge   "M"
    :openai      "O"
    :cursor      "Cu"
    :ollama      "L"
    "?"))

(defn- relative-time
  "Simple relative time string from a Date."
  [date]
  (when date
    (let [ms (- (System/currentTimeMillis) (.getTime date))
          secs (quot ms 1000)
          mins (quot secs 60)]
      (cond
        (< secs 60) (str secs "s ago")
        (< mins 60) (str mins "m ago")
        :else (str (quot mins 60) "h ago")))))

(defn- priority-class [priority]
  (case priority
    :critical "priority-critical"
    :high     "priority-high"
    :medium   "priority-medium"
    :low      "priority-low"
    "priority-medium"))

;------------------------------------------------------------------------------ Layer 0
;; Agent card fragments

(defn agent-card
  "Single agent card for the grid."
  [agent-record]
  (let [agent-id (str (:agent/id agent-record))
        status (:agent/status agent-record)
        vendor (:agent/vendor agent-record)]
    [:div.cp-agent-card {:class (status-class status)
                         :data-agent-id agent-id}
     [:div.cp-card-header
      [:span.cp-vendor-badge {:title (name vendor)} (vendor-icon vendor)]
      [:span.cp-agent-name (or (:agent/name agent-record) "Unnamed Agent")]
      [:span.cp-status-badge {:class (status-class status)}
       (name status)]]
     [:div.cp-card-body
      (when-let [task (:agent/task agent-record)]
        [:div.cp-task [:span.label "Task:"] [:span.value task]])
      [:div.cp-heartbeat
       [:span.label "Last seen:"]
       [:span.value (or (relative-time (:agent/last-heartbeat agent-record)) "never")]]]
     [:div.cp-card-actions
      (when (#{:running :idle} status)
        [:button.btn.btn-xs.btn-ghost
         {:hx-post (str "/api/control-plane/agents/" agent-id "/command")
          :hx-vals "{\"command\":\"pause\"}"
          :hx-swap "none"}
         "Pause"])
      (when (= :paused status)
        [:button.btn.btn-xs.btn-ghost
         {:hx-post (str "/api/control-plane/agents/" agent-id "/command")
          :hx-vals "{\"command\":\"resume\"}"
          :hx-swap "none"}
         "Resume"])
      (when (not (#{:completed :failed :terminated} status))
        [:button.btn.btn-xs.btn-danger
         {:hx-post (str "/api/control-plane/agents/" agent-id "/command")
          :hx-vals "{\"command\":\"terminate\"}"
          :hx-swap "none"
          :hx-confirm "Terminate this agent?"}
         "Kill"])]]))

(defn agents-grid-fragment
  "Agent cards grid fragment for htmx updates."
  [agents]
  (html
   (if (empty? agents)
     [:div.cp-empty-state
      [:div.empty-icon "🤖"]
      [:h3 "No Agents Registered"]
      [:p "Agents will appear here when they register via the API or are discovered by adapters."]
      [:div.cp-register-hint
       [:code "POST /api/control-plane/agents/register"]]]
     [:div.cp-agents-grid
      (for [agent (sort-by (fn [a]
                             [(case (:agent/status a)
                                :blocked 0
                                :running 1
                                :idle 2
                                :unreachable 3
                                9)
                              (str (:agent/name a))])
                           agents)]
        (agent-card agent))])))

;------------------------------------------------------------------------------ Layer 1
;; Decision queue fragments

(defn decision-item
  "Single decision row in the queue."
  [decision]
  (let [decision-id (str (:decision/id decision))]
    [:div.cp-decision-item {:class (priority-class (:decision/priority decision))
                            :data-decision-id decision-id}
     [:div.cp-decision-header
      [:span.cp-priority-badge {:class (priority-class (:decision/priority decision))}
       (str/upper-case (name (:decision/priority decision)))]
      [:span.cp-decision-type (name (or (:decision/type decision) :choice))]
      [:span.cp-decision-time (relative-time (:decision/created-at decision))]]
     [:div.cp-decision-summary (:decision/summary decision)]
     (when-let [context (:decision/context decision)]
       [:div.cp-decision-context context])
     [:div.cp-decision-actions
      (if-let [options (:decision/options decision)]
        ;; Structured choices
        [:div.cp-option-buttons
         (for [opt options]
           [:button.btn.btn-sm
            {:hx-post (str "/api/control-plane/decisions/" decision-id "/resolve")
             :hx-vals (str "{\"resolution\":\"" opt "\"}")
             :hx-target "closest .cp-decision-item"
             :hx-swap "outerHTML"}
            opt])]
        ;; Free-form input
        [:form.cp-decision-form
         {:hx-post (str "/api/control-plane/decisions/" decision-id "/resolve")
          :hx-target "closest .cp-decision-item"
          :hx-swap "outerHTML"}
         [:input.cp-input {:type "text" :name "resolution"
                           :placeholder "Type your response..."}]
         [:button.btn.btn-sm.btn-primary {:type "submit"} "Send"]])]]))

(defn decision-queue-fragment
  "Decision queue fragment for htmx updates."
  [decisions]
  (html
   (if (empty? decisions)
     [:div.cp-empty-decisions
      [:span.check-icon "✓"]
      [:span "No decisions pending — all agents are autonomous"]]
     [:div.cp-decision-queue
      (for [d decisions]
        (decision-item d))])))

;------------------------------------------------------------------------------ Layer 2
;; Summary bar

(defn summary-bar
  "Top-level stats bar."
  [stats]
  (let [{:keys [total-agents by-status pending-decisions]} stats]
    [:div.cp-summary-bar
     [:div.cp-stat
      [:span.cp-stat-value (str total-agents)]
      [:span.cp-stat-label "Agents"]]
     [:div.cp-stat
      [:span.cp-stat-value (str (get by-status :running 0))]
      [:span.cp-stat-label "Running"]]
     [:div.cp-stat.stat-attention
      [:span.cp-stat-value (str (+ (get by-status :blocked 0)
                                    (get by-status :unreachable 0)))]
      [:span.cp-stat-label "Need Attention"]]
     [:div.cp-stat.stat-decisions
      [:span.cp-stat-value (str pending-decisions)]
      [:span.cp-stat-label "Decisions"]]]))

;------------------------------------------------------------------------------ Layer 2
;; Full page content

(defn control-plane-content
  "Main control plane page content (used inside layout)."
  [agents decisions stats]
  [:div.cp-page
   [:div.cp-header
    [:h2 "Agent Control Plane"]
    [:p.cp-subtitle "Unified management for all your AI agents"]]
   (summary-bar stats)
   [:div.cp-two-column
    [:div.cp-column-main
     [:div.cp-section
      [:h3 "Agents"
       [:span.cp-count (str " (" (count agents) ")")]]
      [:div#cp-agents-grid
       {:hx-get "/api/control-plane/agents-grid"
        :hx-trigger "every 5s"
        :hx-swap "innerHTML"}
       (agents-grid-fragment agents)]]]
    [:div.cp-column-sidebar
     [:div.cp-section
      [:h3 "Decision Queue"
       (when (seq decisions)
         [:span.cp-count.cp-count-attention
          (str " (" (count decisions) ")")])]
      [:div#cp-decision-queue
       {:hx-get "/api/control-plane/decisions-queue"
        :hx-trigger "every 3s"
        :hx-swap "innerHTML"}
       (decision-queue-fragment decisions)]]]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test rendering
  (def test-agent {:agent/id (random-uuid)
                   :agent/vendor :claude-code
                   :agent/name "Test Agent"
                   :agent/status :running
                   :agent/task "Reviewing PR #42"
                   :agent/last-heartbeat (java.util.Date.)})
  (html (agent-card test-agent))
  :end)
