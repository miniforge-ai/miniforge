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

(ns ai.miniforge.event-stream.interface.control-state
  "Control-state helpers for the event-stream public API."
  (:require
   [ai.miniforge.event-stream.control :as control]))

;------------------------------------------------------------------------------ Layer 0
;; Control state primitives

(def create-control-state control/create-control-state)
(def pause! control/pause!)
(def resume! control/resume!)
(def cancel! control/cancel!)
(def paused? control/paused?)
(def cancelled? control/cancelled?)
