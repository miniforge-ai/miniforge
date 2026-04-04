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

(ns ai.miniforge.release-executor.result
  "Result builders for release-executor operations.
   Provides consistent result structures across all operations.")

;------------------------------------------------------------------------------ Layer 0
;; Result predicates

(defn succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

;; Shell operation results

(defn shell-success
  "Create a shell operation success result."
  [data]
  (merge {:success? true} data))

(defn shell-failure
  "Create a shell operation failure result."
  ([error-msg]
   (shell-failure error-msg {}))
  ([error-msg data]
   (merge {:success? false :error error-msg} data)))

;------------------------------------------------------------------------------ Layer 1
;; Phase execution results

(defn phase-success
  "Create a release phase success result."
  [artifacts metrics]
  {:success? true
   :artifacts artifacts
   :errors []
   :metrics metrics})

(defn phase-failure
  "Create a release phase failure result."
  ([error-type error-msg]
   (phase-failure error-type error-msg {}))
  ([error-type error-msg opts]
   {:success? false
    :artifacts []
    :errors [(merge {:type error-type :message error-msg}
                    (select-keys opts [:hint :data]))]
    :metrics (or (:metrics opts) {})}))
