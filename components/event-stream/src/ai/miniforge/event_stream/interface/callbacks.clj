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

(ns ai.miniforge.event-stream.interface.callbacks
  "Streaming callback helpers for the event-stream public API."
  (:require
   [clojure.string :as str]
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Convenience callbacks

(defn- tool-use-line
  "Render a short terminal-visible line for a tool-use streaming event."
  [{:keys [tool-name tool-names]}]
  (let [names (or (seq tool-names)
                  (when tool-name [tool-name]))]
    (when (seq names)
      (str "\n[tool] " (str/join ", " names) "\n"))))

(defn create-streaming-callback
  "Callback invoked by the LLM client for each parsed stream event.

   Expected parsed-event keys:
     :delta       — text content produced in this block
     :done?       — terminal block
     :tool-use    — boolean, true when this block invoked one or more tools
     :tool-name   — single tool name when available (Claude / codex)
     :tool-names  — vector of tool names (Claude multi-tool blocks)
     :tool-call-id — provider-supplied id when available
     :tool-args-preview — bounded args digest when the parser can safely
                          expose it
     :heartbeat   — keepalive, ignored for diagnostic emission

   For tool-use events emits BOTH:
     :agent/tool-call  — structured, carries :tool/name / :tool/names /
                          :tool/call-id / :tool/args-preview
     :agent/status     — legacy generic 'Agent calling tool' status-type
                          :tool-calling, preserved so existing consumers
                          (dashboard, TUI, tests) keep working during the
                          migration."
  [stream-atom workflow-id agent-id & [opts]]
  (let [{:keys [print? quiet?]} opts]
    (fn [{:keys [delta done? tool-use heartbeat
                 tool-name tool-names tool-call-id tool-args-preview]}]
      (cond
        tool-use
        (do
          (when-let [line (and print? (not quiet?)
                               (tool-use-line {:tool-name tool-name
                                               :tool-names tool-names}))]
            (print line)
            (flush))
          (when stream-atom
            (stream/publish!
              stream-atom
              (events/agent-tool-call stream-atom workflow-id agent-id
                                      {:tool-name tool-name
                                       :tool-names tool-names
                                       :tool-call-id tool-call-id
                                       :tool-args-preview tool-args-preview}))
            (stream/publish!
              stream-atom
              (events/agent-status stream-atom workflow-id agent-id
                                   :tool-calling "Agent calling tool"))))

        heartbeat
        nil

        :else
        (do
          (when (and print? (not quiet?) delta (not (empty? delta)))
            (print delta)
            (flush))
          (when stream-atom
            (stream/publish! stream-atom
                             (events/agent-chunk stream-atom workflow-id agent-id
                                                 (or delta "") done?))))))))
