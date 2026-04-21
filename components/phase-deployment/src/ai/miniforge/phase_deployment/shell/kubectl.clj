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

(ns ai.miniforge.phase-deployment.shell.kubectl
  "kubectl CLI wrappers for deployment phases."
  (:require [ai.miniforge.phase-deployment.shell.exec :as exec]))

;------------------------------------------------------------------------------ Layer 0
;; kubectl wrappers

(defn kubectl!
  "Execute a kubectl command."
  [subcommand & {:keys [namespace context output extra-args timeout-ms]
                 :or {timeout-ms 120000}}]
  (let [args (cond-> [subcommand]
               namespace  (into ["--namespace" namespace])
               context    (into ["--context" context])
               output     (into ["-o" output])
               extra-args (into extra-args))
        result (exec/sh-with-timeout "kubectl" args :timeout-ms timeout-ms)]
    (if (= output "json")
      (exec/with-parsed-json result)
      result)))

(defn kubectl-rollout-status!
  [resource & {:keys [namespace context timeout-s]
               :or {timeout-s 300}}]
  (kubectl! "rollout"
            :namespace namespace
            :context context
            :extra-args ["status" resource (str "--timeout=" timeout-s "s")]
            :timeout-ms (* (+ timeout-s 30) 1000)))

(defn kubectl-get-pods!
  [selector & {:keys [namespace context]}]
  (kubectl! "get"
            :namespace namespace
            :context context
            :output "json"
            :extra-args ["pods" "-l" selector]))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (kubectl! "get" :extra-args ["pods"] :namespace "default" :output "json")
  :leave-this-here)
