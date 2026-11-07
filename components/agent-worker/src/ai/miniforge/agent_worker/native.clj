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
(ns ai.miniforge.agent-worker.native
  "Native Claude and Codex workers on the existing llm backends. A turn runs
   the backend's own command line in the background inside the leased
   environment; the backend's own stream parser reads the native session id
   and usage. Per-kind settings live in config/agent-worker/native.edn."
  (:require
   [ai.miniforge.agent-worker.interface.protocol :as protocol]
   [ai.miniforge.agent-worker.messages :as msg]
   [ai.miniforge.agent-worker.native.settings :as settings]
   [ai.miniforge.agent-worker.native.shell :as shell]
   [ai.miniforge.agent-worker.native.turn :as turn]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} NativeWorker [native]
  protocol/AgentWorker
  (capabilities [_] (:native/capabilities native))
  (preflight [_ handle] (turn/preflight-failures native handle))
  (start! [_ handle submission]
    (turn/launch! native handle (:submission/config submission)
                  (get-in submission [:submission/binding :worker/attempt]) 1 (:submission/prompt submission) nil))
  (inspect [_ handle session] (turn/status handle session))
  (send-input! [_ handle submission session text]
    (if-let [native-id (or (:session/native-id session) (:stream/native-id (turn/read-stream native handle session)))]
      (turn/launch! native handle (:submission/config submission) (get-in session [:session/binding :worker/attempt])
                    (inc (:session/turn session)) text native-id)
      (anomaly/sub-anomaly :conflict :anomalies.agent-worker/native-session-unknown
                           (msg/t :anomalies.agent-worker/native-session-unknown) {})))
  (request-cancel! [_ handle session] (turn/cancel! handle session))
  (artifact-paths [_ _handle session]
    (let [[dir _] (shell/split-ref (:session/process-ref session))]
      [(shell/turn-file dir "out.jsonl") (shell/turn-file dir "err.log")]))
  (usage [_ handle session]
    (:stream/usage (turn/read-stream native handle session))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create
  "The native worker for `kind` (`:worker/claude` or `:worker/codex`), or a
   `worker-unavailable` anomaly for a kind with no settings."
  [kind]
  (if-let [native (settings/for-kind kind)]
    (->NativeWorker native)
    (anomaly/sub-anomaly :unavailable :anomalies.agent-worker/worker-unavailable
                         (msg/t :anomalies.agent-worker/worker-unavailable) {:worker kind})))
