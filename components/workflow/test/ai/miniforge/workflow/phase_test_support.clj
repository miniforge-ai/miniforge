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

(ns ai.miniforge.workflow.phase-test-support
  "Test-only phase registrations for workflow unit tests.

   These tests exercise runner and execution mechanics under the narrowed
   stable-derived project classpath. They should not depend on software-factory
   phase registrations being ambient on the classpath."
  (:require
   [ai.miniforge.phase.registry :as registry]))

(def runner-test-plan
  :runner-test-plan)

(def runner-test-implement
  :runner-test-implement)

(def runner-test-verify
  :runner-test-verify)

(def runner-test-done
  :runner-test-done)

(def ^:private workflow-test-phase-defaults
  {:agent :workflow-tester
   :gates [:workflow-synthetic-gate]
   :budget {:tokens 1
            :iterations 1
            :time-seconds 1}})

(defn- register-workflow-test-phase!
  [phase-name]
  (registry/register-phase-defaults!
   phase-name
   (assoc workflow-test-phase-defaults :phase phase-name)))

(defmacro ^:private def-workflow-test-phase
  [phase-name]
  `(do
     (defmethod registry/get-phase-interceptor ~phase-name
       [config#]
       {:name ~(keyword "ai.miniforge.workflow.phase-test-support"
                        (name phase-name))
        :config (registry/merge-with-defaults config#)
        :enter identity
        :leave identity
        :error (fn [ctx# _ex#] ctx#)})
     (register-workflow-test-phase! ~phase-name)))

(def-workflow-test-phase runner-test-plan)
(def-workflow-test-phase runner-test-implement)
(def-workflow-test-phase runner-test-verify)

(defmethod registry/get-phase-interceptor runner-test-done
  [config]
  {:name ::runner-test-done
   :config (registry/merge-with-defaults config)
   :enter (fn [ctx]
            (-> ctx
                (assoc-in [:phase :name] runner-test-done)
                (assoc-in [:phase :status] :completed)
                (assoc-in [:execution/status] :completed)))
   :leave identity
   :error (fn [ctx _ex] ctx)})

(register-workflow-test-phase! runner-test-done)
