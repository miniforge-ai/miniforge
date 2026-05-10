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
  (:require
   [ai.miniforge.phase.interface :as phase]))

(def runner-test-plan
  :runner-test-plan)

(def runner-test-implement
  :runner-test-implement)

(def runner-test-verify
  :runner-test-verify)

(def runner-test-done
  :runner-test-done)

(def ^:private phase-budget
  {:tokens 1
   :iterations 1
   :time-seconds 1})

(def ^:private phase-defaults
  {:agent :workflow-tester
   :gates []
   :budget phase-budget})

(defn- complete-phase
  [ctx phase-name]
  (-> ctx
      (assoc-in [:phase :name] phase-name)
      (assoc-in [:phase :status] :completed)
      (assoc-in [:phase :metrics] {:tokens 0 :duration-ms 0})
      (assoc-in [:phase :result]
                (phase/success (:execution/environment-id ctx)
                               (str (name phase-name) " completed")
                               {:tokens 0 :duration-ms 0}))))

(defn- enter-phase
  [ctx phase-name]
  (phase/enter-context ctx
                       phase-name
                       :workflow-tester
                       []
                       phase-budget
                       (System/currentTimeMillis)
                       nil))

(defn- register-phase!
  [phase-name]
  (phase/register-phase-defaults!
   phase-name
   (assoc phase-defaults :phase phase-name)))

(defmethod phase/get-phase-interceptor-method runner-test-plan
  [config]
  {:name ::runner-test-plan
   :config (phase/merge-with-defaults config)
   :enter #(enter-phase % runner-test-plan)
   :leave #(complete-phase % runner-test-plan)
   :error (fn [ctx _ex] ctx)})

(defmethod phase/get-phase-interceptor-method runner-test-implement
  [config]
  {:name ::runner-test-implement
   :config (phase/merge-with-defaults config)
   :enter #(enter-phase % runner-test-implement)
   :leave #(complete-phase % runner-test-implement)
   :error (fn [ctx _ex] ctx)})

(defmethod phase/get-phase-interceptor-method runner-test-verify
  [config]
  {:name ::runner-test-verify
   :config (phase/merge-with-defaults config)
   :enter #(enter-phase % runner-test-verify)
   :leave #(complete-phase % runner-test-verify)
   :error (fn [ctx _ex] ctx)})

(defmethod phase/get-phase-interceptor-method runner-test-done
  [config]
  {:name ::runner-test-done
   :config (phase/merge-with-defaults config)
   :enter #(enter-phase % runner-test-done)
   :leave #(complete-phase % runner-test-done)
   :error (fn [ctx _ex] ctx)})

(register-phase! runner-test-plan)
(register-phase! runner-test-implement)
(register-phase! runner-test-verify)
(register-phase! runner-test-done)
