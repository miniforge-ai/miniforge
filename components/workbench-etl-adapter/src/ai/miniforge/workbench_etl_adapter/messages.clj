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

(ns ai.miniforge.workbench-etl-adapter.messages
  "System-locale messages emitted in ETL workbench findings."
  (:require
   [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def t
  "Translate an adapter system-message key with optional interpolation data."
  (messages/create-translator
   "config/workbench-etl-adapter/messages/system.edn"
   :workbench-etl-adapter/system))
