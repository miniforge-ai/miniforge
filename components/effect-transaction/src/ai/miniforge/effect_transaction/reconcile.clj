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
(ns ai.miniforge.effect-transaction.reconcile
  "Settle unknown effect outcomes by asking the external system."
  (:require
   [ai.miniforge.effect-transaction.call :as call]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.record :as record]
   [ai.miniforge.effect-transaction.schema :as schema])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-answer!
  "Persist the external system's answer to a reconciliation probe."
  [dir t answer ^Instant now]
  (record/advance! dir t
                   {:effect/state :reconciled
                    :effect/observed (:effect/observed answer)
                    :effect/matched? (boolean (:effect/matched? answer))}
                   now))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} probe!
  "Probe the external system and preserve an unresolved record on silence."
  [dir t probe-fn ^Instant now]
  (let [called (call/result (partial probe-fn t))]
    (if (call/probe-answered? called)
      (record-answer! dir t (:call/report called) now)
      (record/unresolved (msg/t :reconcile/no-answer)
                         (call/probe-error-data t called)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reconcile!
  "Ask the world to settle a committing or unknown-outcome record."
  [dir t probe-fn ^Instant now]
  (if-not (contains? schema/reconcilable-states (:effect/state t))
    (record/wrong-state (msg/t :reconcile/not-reconcilable)
                        {:effect/id (:effect/id t)
                         :effect/state (:effect/state t)})
    (probe! dir t probe-fn now)))
