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

(ns ai.miniforge.event-stream.interface.stream
  "Event-stream lifecycle and query API."
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream lifecycle and queries

(def create-event-stream core/create-event-stream)
(def create-envelope core/create-envelope)
(def publish! core/publish!)
(def subscribe! core/subscribe!)
(def unsubscribe! core/unsubscribe!)
(def get-events core/get-events)
(def get-latest-status core/get-latest-status)
