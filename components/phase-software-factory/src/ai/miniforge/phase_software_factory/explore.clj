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

(ns ai.miniforge.phase-software-factory.explore
  "Explore phase interceptor.

   Deterministic file-loading phase that runs before planning.
   Loads files-in-scope from disk and passes discovery results
   to downstream phases (plan, implement) so they can make
   informed decisions about existing code state.

   Agent: nil (no LLM — deterministic file loading)
   Default gates: []"
  (:require [ai.miniforge.phase.interface :as phase]
            
            [ai.miniforge.knowledge.interface :as knowledge]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent nil
   :gates []
   :budget {:tokens 5000
            :iterations 1
            :time-seconds 120}
   :max-files 25
   :max-lines-per-file 500
   :model-hint :haiku-4.5})

;; Register defaults on load
(phase/register-phase-defaults! :explore default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-explore
  "Execute exploration phase.

   Reads files-in-scope from execution input, loads their contents
   from disk, and stores the exploration result for downstream phases."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        input (get-in ctx [:execution/input])
        config (get ctx :phase-config default-config)
        files-in-scope (:files-in-scope input)
        worktree-path (or (:worktree-path input)
                          (:execution/worktree-path ctx)
                          ".")

        ;; Load files from disk (deterministic, no LLM)
        loaded-files (phase/load-files-in-scope worktree-path files-in-scope
                                                   {:max-files (:max-files config)
                                                    :max-lines-per-file (:max-lines-per-file config)})

        ;; Query KB for relevant knowledge based on spec description
        spec-desc (or (:description input) (:title input))
        kb-results (when (and (:knowledge-store ctx) (seq spec-desc))
                     (try
                       (take 5 (knowledge/search (:knowledge-store ctx) spec-desc))
                       (catch Exception _e nil)))

        ;; Build exploration result
        exploration (cond-> {:exploration/files (or loaded-files [])
                             :exploration/file-count (count (or loaded-files []))
                             :exploration/spec-description (:description input)}
                      (seq kb-results)
                      (assoc :exploration/knowledge kb-results))]

    (phase/enter-context ctx :explore nil [] (:budget default-config) start-time
                                {:status :success :output exploration})))

(defn leave-explore
  "Post-processing for exploration phase.

   Records metrics (file count, load time)."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        file-count (get-in result [:output :exploration/file-count] 0)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:file-count file-count
                                     :duration-ms duration-ms})
        (update-in [:execution :phases-completed] (fnil conj []) :explore)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-explore
  "Handle exploration phase errors. Simple retry within budget;
   delegates to the shared `phase/handle-error` helper."
  [ctx ex]
  (phase/handle-error ctx ex 1))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod phase/get-phase-interceptor-method :explore
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::explore
     :config merged
     :enter (fn [ctx]
              (enter-explore (assoc ctx :phase-config merged)))
     :leave leave-explore
     :error error-explore}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get explore interceptor with defaults
  (phase/get-phase-interceptor {:phase :explore})

  ;; Check defaults
  (phase/phase-defaults :explore)

  :leave-this-here)
