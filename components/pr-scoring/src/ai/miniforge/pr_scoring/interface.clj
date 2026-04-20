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

(ns ai.miniforge.pr-scoring.interface
  "Public API for the pr-scoring component (N5-delta-2 §3).

   Produces `:pr/scored` events by invoking a pluggable scorer-fn on
   fine-grained PR events. Consumed exclusively by `supervisory-state`,
   which merges the scored fields into the canonical `PrFleetEntry`.

   Typical use:

       (require '[ai.miniforge.event-stream.interface :as es]
                '[ai.miniforge.pr-scoring.interface :as scoring])

       (def stream  (es/create-event-stream))
       (def scorer  (scoring/attach! stream {:scorer-fn my-scorer}))
       ;; … PR events flow through the stream; :pr/scored emitted
       ;;   whenever my-scorer returns non-nil …
       (scoring/stop! scorer)"
  (:require
   [ai.miniforge.pr-scoring.core :as core]))

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle

(def create  core/create)
(def start!  core/start!)
(def stop!   core/stop!)
(def attach! core/attach!)

;------------------------------------------------------------------------------ Layer 2
;; Extension points

(def default-scorer-fn           core/default-scorer-fn)
(def load-default-triggers       core/load-default-triggers)
(def default-trigger-event-types core/default-trigger-event-types)
(def trigger-config-resource     core/trigger-config-resource)
