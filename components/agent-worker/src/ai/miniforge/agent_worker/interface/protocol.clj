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
(ns ai.miniforge.agent-worker.interface.protocol
  "What a worker supplies: a harness that runs one attempt inside a leased
   environment. `env` is `{:env/controller :env/acting :env/lease}`; every
   command a worker runs goes through that environment's lifecycle, so a
   stale lease stops it. Workers decide no tenancy, grant or acceptance.")

;------------------------------------------------------------------------------ Layer 0

(defprotocol ^{:stratum 0} AgentWorker
  (capabilities [worker]
    "The worker's declared `Capabilities`.")
  (preflight [worker env]
    "Failure reasons inside `env`, empty when the worker can run there.")
  (start! [worker env submission]
    "Launch the attempt without waiting for it to end. Returns
     `{:native/id s-or-nil :native/process-ref s}` or an anomaly.")
  (inspect [worker env session]
    "The session's `Status`.")
  (send-input! [worker env submission session text]
    "Start the next turn of a finished turn's native session with `text`,
     under the original `submission`'s config. Returns new native
     references, or an anomaly.")
  (request-cancel! [worker env session]
    "Ask the running turn to stop: `{:cancel/requested? bool}`. Whether it
     stopped is for `inspect` to say.")
  (artifact-paths [worker env session]
    "Paths inside the environment the session wrote.")
  (usage [worker env session]
    "What the harness reported: `:usage/input-tokens`, `:usage/output-tokens`,
     `:usage/cost-usd`, each absent when not reported."))
