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
(ns ai.miniforge.agent-worker.native.turn
  "One native turn's operations inside the environment: launch, status,
   cancel, preflight checks and reading the stream."
  (:require
   [ai.miniforge.agent-worker.messages :as msg]
   [ai.miniforge.agent-worker.native.command :as command]
   [ai.miniforge.agent-worker.native.settings :as settings]
   [ai.miniforge.agent-worker.native.shell :as shell]
   [ai.miniforge.agent-worker.native.stream :as stream]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.interface :as llm]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} succeeded? [result]
  (and (not (anomaly/anomaly? result)) (zero? (:exec/exit result))))

(defn- ^{:stratum 0} launch-failed [result]
  (if (anomaly/anomaly? result)
    result
    (anomaly/sub-anomaly :fault :anomalies.agent-worker/launch-failed (msg/t :anomalies.agent-worker/launch-failed)
                         {:exec/exit (:exec/exit result)})))

(defn- ^{:stratum 0} run-script! [handle script] (command/sh! handle (settings/timeout-ms) script))

(defn- ^{:stratum 0} stream-text [handle session]
  (let [[dir _] (shell/split-ref (:session/process-ref session))
        text (command/read-text handle (shell/turn-file dir "out.jsonl"))]
    (if (anomaly/anomaly? text) "" text)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} launch!
  "Write the turn's prompt (and settings), start the harness, and return its
   native references; a launch that printed no pid failed."
  [native handle config attempt turn text native-id]
  (let [workdir (:config/workdir config)
        dir (shell/run-dir workdir attempt turn)
        prompt (command/write! handle (shell/turn-file dir "prompt.txt") text)
        written (if (and (not (anomaly/anomaly? prompt)) (:native/settings-file? native))
                  (anomaly/let-ok [_ (command/write! handle (shell/turn-file dir "settings.json") (command/settings-json native))]
                    (command/write! handle (shell/turn-file dir "mcp.json") command/no-mcp-servers))
                  prompt)
        script (shell/launch-script workdir dir (command/argv native config dir native-id))
        launched (if (anomaly/anomaly? written) written (run-script! handle script))
        pid (when (succeeded? launched) (re-matches #"\d+" (str/trim (str (:exec/stdout launched)))))]
    (if pid
      {:native/id native-id :native/process-ref (shell/process-ref dir pid)}
      (launch-failed launched))))

(defn ^{:stratum 1} read-stream [native handle session]
  (stream/read-stream (:stream-parser (command/backend native)) (stream-text handle session)))

(defn ^{:stratum 1} preflight-failures [native handle]
  (let [{:keys [cmd]} (command/backend native)
        credentials (llm/backend-credential-env (:native/backend native))]
    (cond-> []
      (not (succeeded? (run-script! handle (shell/present-script cmd))))
      (conj :anomalies.agent-worker/cli-missing)

      (not (succeeded? (run-script! handle (shell/credential-script credentials))))
      (conj :anomalies.agent-worker/credential-missing))))

(defn ^{:stratum 1} status [handle session]
  (let [[dir pid] (shell/split-ref (:session/process-ref session))
        result (run-script! handle (shell/status-script dir pid))]
    (shell/status-of (when-not (anomaly/anomaly? result) (:exec/stdout result)))))

(defn ^{:stratum 1} cancel! [handle session]
  (let [[_ pid] (shell/split-ref (:session/process-ref session))]
    {:cancel/requested? (succeeded? (run-script! handle (shell/cancel-script pid)))}))
