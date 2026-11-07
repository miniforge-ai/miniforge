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
(ns ai.miniforge.agent-worker.fake.turns
  "The fake worker's turns: each writes its input into the leased
   environment and is recorded in the fake's state."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prompt-path
  [submission turn]
  (str (get-in submission [:submission/config :config/workdir])
       "/.miniforge/fake-worker-" (get-in submission [:submission/binding :worker/attempt]) "-" turn ".txt"))

(defn- ^{:stratum 0} write!
  [handle path text]
  (env/write-file! (:env/controller handle) (:env/acting handle) (:env/lease handle) path text))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} turn!
  "Write one turn's input into the environment and record the turn."
  [state submission turn text handle]
  (let [path (prompt-path submission turn)
        written (write! handle path text)
        ref (str "fake-process-" (random-uuid))]
    (if (anomaly/anomaly? written)
      written
      (do (swap! state assoc ref {:path path :cancelled? false})
          (swap! state update :fake/starts (fnil inc 0))
          {:native/id (str "fake-session-" (get-in submission [:submission/binding :worker/attempt]))
           :native/process-ref ref}))))
