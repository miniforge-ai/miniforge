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

(def approval-succeeded?
  "Return true when a builder response (from response/success)
   succeeded, else false. Use to test submit-approval / cancel-approval
   results."
  approval/succeeded?)

(def approval-failed?
  "Return true when a builder response (from response/failure) failed,
   else false. Use to test submit-approval / cancel-approval results."
  approval/failed?)

(def create-approval-request
  "Create a new approval-request map requiring quorum-based signing.
   Args: action-id (UUID), required-signers (vector of strings), quorum
   (count), and optional opts (:expires-in-hours default 24,
   :metadata). Returns a map with :approval/id, :approval/action-id,
   :approval/status :pending, :approval/required-signers,
   :approval/quorum, :approval/signatures [], :approval/created-at,
   :approval/expires-at, plus :approval/metadata when supplied."
  approval/create-approval-request)

(def submit-approval
  "Submit an :approve or :reject decision from a signer for an approval
   request. On success returns a builder success wrapping the updated
   request (status transitions to :approved on quorum, :rejected
   immediately on any reject, else stays :pending). Returns a builder
   failure (anomaly) when not pending, expired, signer unauthorized,
   already signed, or the decision is invalid. Optional opts: :reason."
  approval/submit-approval)

(def check-approval-status
  "Return the effective status keyword of an approval request, mapping
   a pending-but-expired request to :expired. One of :pending,
   :approved, :rejected, :expired, or :cancelled."
  approval/check-approval-status)

(def cancel-approval
  "Cancel a pending approval request, recording canceller and reason.
   Returns a builder success wrapping the updated request (status
   :cancelled), or a builder failure when the request is not pending."
  approval/cancel-approval)

(def create-approval-manager
  "Create an atom-backed approval manager. Returns an atom holding
   {:approvals {}} keyed by approval id."
  approval/create-approval-manager)

(def store-approval!
  "Store an approval request in the manager atom under its :approval/id.
   Returns the stored approval map."
  approval/store-approval!)

(def get-approval
  "Look up an approval request in the manager atom by id. Returns the
   approval map, or nil when absent."
  approval/get-approval)

(def update-approval!
  "Replace an approval request in the manager atom (keyed by its
   :approval/id). Returns the updated approval map."
  approval/update-approval!)

(def list-approvals
  "List approval requests held by the manager atom. Returns a vector of
   approval maps. Optional opts :status filters by effective status
   (via check-approval-status)."
  approval/list-approvals)
