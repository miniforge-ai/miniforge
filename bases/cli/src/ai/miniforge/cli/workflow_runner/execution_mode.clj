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
(ns ai.miniforge.cli.workflow-runner.execution-mode
  "The execution mode a run uses, and the config that can refuse :local.

   :local runs the agent in a host worktree with the operator's
   privileges; :governed runs it in a container capsule. A user config
   that sets [:execution :require-governed?] to true makes :governed the
   default and refuses any run that would execute in another mode, so
   an operator can rule unisolated runs out instead of opting each run
   into isolation. The workflow runner checks again where it acquires a
   run's environment; this namespace refuses early, with the CLI's message."
  (:require
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.config.interface :as config-interface]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} execution-modes
  "The execution modes a run may name."
  #{:local :governed})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-mode
  "The execution mode for a run with `opts` under user config `cfg`: the
   explicit :execution-mode (a keyword or its name), else :governed when
   `cfg` requires it, else :local. Returns {:execution-mode mode};
   {:unknown value} for a value outside `execution-modes`; or
   {:refused mode} when `cfg` requires governed execution and the mode is
   anything else."
  [opts cfg]
  (let [required? (config-interface/governed-required? cfg)
        given     (get opts :execution-mode)
        mode      (cond
                    (nil? given)    (if required? :governed :local)
                    (string? given) (keyword given)
                    :else           given)]
    (cond
      (not (contains? execution-modes mode)) {:unknown given}
      (and required? (not= :governed mode))  {:refused mode}
      :else                                  {:execution-mode mode})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} allowed-mode!
  "The execution mode for a run with `opts`, checked against the user
   config. Throws an :anomalies/incorrect anomaly for an unknown mode, and
   an :anomalies/forbidden anomaly when the config requires governed
   execution and the run would execute otherwise. The runners call it
   before workflow setup."
  [opts]
  (let [{:keys [execution-mode refused] :as resolved} (resolve-mode opts (config/load-config))]
    (when (contains? resolved :unknown)
      (response/throw-anomaly! :anomalies/incorrect
                               (messages/t :workflow-runner/unknown-execution-mode
                                           {:mode (pr-str (:unknown resolved))})
                               {:execution-mode (:unknown resolved)}))
    (when refused
      (response/throw-anomaly! :anomalies/forbidden
                               (messages/t :workflow-runner/governed-required
                                           {:mode (name refused)})
                               {:execution-mode refused}))
    execution-mode))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (resolve-mode {} {})
  ;; => {:execution-mode :local}

  (resolve-mode {} {:execution {:require-governed? true}})
  ;; => {:execution-mode :governed}

  (resolve-mode {:execution-mode :local} {:execution {:require-governed? true}})
  ;; => {:refused :local}

  :end)
