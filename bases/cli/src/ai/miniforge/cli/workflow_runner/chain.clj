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
(ns ai.miniforge.cli.workflow-runner.chain
  "Chain-driven execution: resolve chain input from a spec file or
   inline JSON, print the chain banner/result, and run a chain of
   workflows under the governed control path. Split out of
   `ai.miniforge.cli.workflow-runner` (rule 210: the parent namespace
   measured 10 real layers, max 3; each concern moves to its own
   layer-coherent file)."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} resolve-chain-input
  "Resolve chain input from a spec file path or inline JSON."
  [opts]
  (let [spec-path (:spec opts)
        inline-json (:input-json opts)]
    (cond
      inline-json (json/parse-string inline-json true)
      spec-path (let [parsed (edn/read-string (slurp spec-path))
                      enriched (context/decorate-spec-with-runtime-context parsed {})]
                  (context/spec->workflow-input enriched))
      :else {})))

(defn ^{:stratum 0} print-chain-header
  "Print chain execution banner."
  [chain-id chain-def quiet]
  (when-not quiet
    (println)
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-header {:chain-id (name chain-id)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-description {:description (:chain/description chain-def)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-steps {:count (count (:chain/steps chain-def))})))
    (println (display/colorize :cyan (apply str (repeat 60 "─"))))))

(defn ^{:stratum 0} print-chain-result
  "Print chain execution result summary."
  [result quiet]
  (when-not quiet
    (let [steps (:chain/step-results result)
          duration (:chain/duration-ms result)]
      (println)
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (if (phase/succeeded? result)
        (println (display/colorize :green (messages/t :workflow-runner/chain-completed {:count (count steps) :duration duration})))
        (let [failed-step (some #(when (phase/failed? %) (:step/id %)) steps)]
          (println (display/colorize :red (messages/t :workflow-runner/chain-failed-at {:step (when failed-step (name failed-step))}))))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-chain!
  "Execute a chain of workflows.

   Arguments:
   - chain-id: Chain identifier keyword (e.g. :reporting-chain)
   - opts: {:version \"latest\" :spec \"spec.edn\" :input-json \"{...}\" :quiet false}"
  [chain-id opts]
  (let [quiet (get opts :quiet false)
        version (get opts :version "latest")]
    (try
      (let [chain-result (workflow/load-chain chain-id version)
            chain-def (:chain chain-result)
            chain-input (resolve-chain-input opts)
            event-stream (es/create-event-stream)
            _supervisor (supervisory/attach! event-stream)
            ;; N15-6: see the meta-loop attach in the parent namespace
            ;; for rationale.
            _correlator (correlator/attach! event-stream)
            llm-client (context/create-llm-client nil nil quiet)
            callbacks (setup/create-phase-callbacks quiet)
            chain-run-id (random-uuid)
            control-state (es/create-control-state)
            context (context/create-workflow-context {:callbacks callbacks
                                                      :event-stream event-stream
                                                      :llm-client llm-client
                                                      :quiet quiet
                                                      :workflow-id chain-run-id
                                                      :workflow-type chain-id
                                                      :workflow-version version
                                                      :spec-title (str "Chain: " (name chain-id))
                                                      :control-state control-state})
            progress-cleanup (display/start-progress! event-stream quiet)]
        (print-chain-header chain-id chain-def quiet)
        (dashboard/print-dashboard-status! quiet)
        (preflight/run-backend-preflight! quiet llm-client context)
        (try
          (control/register-workflow-control! chain-run-id control-state event-stream)
          (let [result (workflow/run-chain chain-def chain-input context)]
            (print-chain-result result quiet)
            result)
          (finally
            (progress-cleanup)
            (control/release-workflow-control! chain-run-id))))
      (catch Exception e
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/chain-execution-failed {:error (ex-message e)}))))
        (throw e)))))
