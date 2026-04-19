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

(ns ai.miniforge.event-stream.agent-tool-call-test
  "Structured :agent/tool-call events carry the tool name(s) the agent
   just invoked. Replaces the opaque :agent/status :tool-calling pings
   that previously gave us no diagnostic information."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as es]))

(defn- stream [] (es/create-event-stream))

(deftest single-tool-call-carries-name
  (testing "single tool name from a Claude-style tool_use block"
    (let [s (stream)
          wf-id (random-uuid)
          ev (es/agent-tool-call s wf-id :planner
                                 {:tool-name "mcp__context__context_read"
                                  :tool-call-id "tu_abc123"})]
      (is (= :agent/tool-call (:event/type ev)))
      (is (= "mcp__context__context_read" (:tool/name ev)))
      (is (= "tu_abc123" (:tool/call-id ev)))
      (is (= :planner (:agent/id ev))))))

(deftest multi-tool-block-carries-names-vector
  (testing "Claude assistant block containing multiple tool_use items"
    (let [s (stream)
          ev (es/agent-tool-call s (random-uuid) :planner
                                 {:tool-names ["Read" "Grep" "Glob"]})]
      (is (= :agent/tool-call (:event/type ev)))
      (is (= ["Read" "Grep" "Glob"] (:tool/names ev)))
      (is (nil? (:tool/name ev))
          "prefer :tool/names for the multi case"))))

(deftest empty-tool-info-does-not-leak-nil-keys
  (testing "missing tool name/id/args do not emit nil-valued keys"
    (let [s (stream)
          ev (es/agent-tool-call s (random-uuid) :planner {})]
      (is (= :agent/tool-call (:event/type ev)))
      (is (not (contains? ev :tool/name)))
      (is (not (contains? ev :tool/names)))
      (is (not (contains? ev :tool/call-id)))
      (is (not (contains? ev :tool/args-preview))))))

(deftest args-preview-is-bounded
  (testing "callers pass a pre-truncated args preview; fn stores it as-is"
    (let [s (stream)
          preview "{:path \"components/agent/src/core.clj\"}"
          ev (es/agent-tool-call s (random-uuid) :planner
                                 {:tool-name "Read" :tool-args-preview preview})]
      (is (= preview (:tool/args-preview ev)))
      (is (< (count (:tool/args-preview ev)) 1024)
          "preview string stays under 1KB per event-sizing convention"))))

(deftest distinct-event-type-from-agent-status
  (testing ":agent/tool-call is a new event type, NOT :agent/status"
    (let [s (stream)
          wf-id (random-uuid)
          tc (es/agent-tool-call s wf-id :planner {:tool-name "Read"})
          st (es/agent-status s wf-id :planner :tool-calling "Agent calling tool")]
      (is (= :agent/tool-call (:event/type tc)))
      (is (= :agent/status (:event/type st)))
      (is (not= (:event/type tc) (:event/type st))))))
