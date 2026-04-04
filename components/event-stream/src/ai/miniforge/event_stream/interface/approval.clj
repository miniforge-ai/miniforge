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

(ns ai.miniforge.event-stream.interface.approval
  "Multi-party approval API for the event stream."
  (:require
   [ai.miniforge.event-stream.approval :as approval]))

;------------------------------------------------------------------------------ Layer 0
;; Approval management

(def approval-succeeded? approval/succeeded?)
(def approval-failed? approval/failed?)
(def create-approval-request approval/create-approval-request)
(def submit-approval approval/submit-approval)
(def check-approval-status approval/check-approval-status)
(def cancel-approval approval/cancel-approval)
(def create-approval-manager approval/create-approval-manager)
(def store-approval! approval/store-approval!)
(def get-approval approval/get-approval)
(def update-approval! approval/update-approval!)
(def list-approvals approval/list-approvals)
