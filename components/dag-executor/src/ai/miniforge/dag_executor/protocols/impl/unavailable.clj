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
(ns ai.miniforge.dag-executor.protocols.impl.unavailable
  "An executor that is never available. It fills a container slot whose
   runtime selection failed (for example an :executable override without
   a :runtime-kind) and carries the selection error: `available?` reports
   it as the reason, and every operation returns it. `select-executor`
   skips the slot, so a rejected configuration never runs."
  (:require
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} selection-failure
  "The selection `error` ({:code :message :data}) as an error result."
  [error]
  (result/err (:code error) (:message error) (:data error)))

;------------------------------------------------------------------------------ Layer 1

(defrecord ^{:stratum 1} UnavailableExecutor [error]
  proto/TaskExecutor
  (executor-type [_this] :unavailable)
  (available? [_this]
    (result/ok {:available? false :reason (:message error) :error error}))
  (acquire-environment! [_this _task-id _config] (selection-failure error))
  (execute! [_this _environment-id _command _opts] (selection-failure error))
  (copy-to! [_this _environment-id _local-path _remote-path] (selection-failure error))
  (copy-from! [_this _environment-id _remote-path _local-path] (selection-failure error))
  (release-environment! [_this _environment-id] (selection-failure error))
  (environment-status [_this _environment-id] (selection-failure error))
  (persist-workspace! [_this _environment-id _opts] (selection-failure error))
  (restore-workspace! [_this _environment-id _opts] (selection-failure error)))
