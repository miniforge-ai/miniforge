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

(ns ai.miniforge.event-stream.interface.control
  "Control-action API for the event stream."
  (:require
   [ai.miniforge.event-stream.control :as control]))

;------------------------------------------------------------------------------ Layer 0
;; Control actions

(def create-control-action control/create-control-action)
(def authorize-action control/authorize-action)
(def execute-control-action! control/execute-control-action!)
(def requires-approval? control/requires-approval?)
(def execute-control-action-with-approval! control/execute-control-action-with-approval!)
