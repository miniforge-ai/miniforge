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
(ns ai.miniforge.agent-worker.native.command
  "What a native turn runs: the llm backend's own command line for this
   config, and the environment calls that carry it."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.native.shell :as shell]
   [ai.miniforge.llm.interface :as llm]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} backend [settings] (get llm/backends (:native/backend settings)))

(defn ^{:stratum 0} request-for
  "The llm args request for one turn: prompt on stdin, streaming JSON, and
   the config's model and budget; a resumed turn names its native session."
  [settings config dir native-id]
  (cond-> {:prompt-via :stdin :streaming? true}
    (:config/model config) (assoc :model (:config/model config))
    (:config/max-budget-usd config) (assoc :budget-usd (:config/max-budget-usd config))
    (:native/settings-file? settings) (assoc :supervision {:settings-path (shell/turn-file dir "settings.json")})
    native-id (assoc :resume native-id)))

(defn ^{:stratum 0} settings-json
  [settings]
  (json/generate-string {:permissions {:allow (:native/allowed-tools settings)}}))

(defn ^{:stratum 0} sh!
  [handle timeout-ms script]
  (env/exec! (:env/controller handle) (:env/acting handle) (:env/lease handle)
             {:exec/argv ["sh" "-c" script] :exec/timeout-ms timeout-ms}))

(defn ^{:stratum 0} write!
  [handle path text]
  (env/write-file! (:env/controller handle) (:env/acting handle) (:env/lease handle) path text))

(defn ^{:stratum 0} read-text
  [handle path]
  (env/read-file (:env/controller handle) (:env/acting handle) (:env/lease handle) path))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} argv
  [settings config dir native-id]
  (let [{:keys [cmd args-fn]} (backend settings)]
    (into [cmd] (args-fn (request-for settings config dir native-id)))))
