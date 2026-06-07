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

(def create-control-action
  "Build a structured control-action map from an action-type keyword
   (:pause, :resume, :retry, :rollback, :cancel, :quarantine,
   :adjust-budget, :emergency-stop, :gate-override, ...), a target map
   ({:target-type :workflow|:agent|:fleet :target-id ...}), and a
   requester map ({:principal :role :listener-id}). Returns a map with
   :action/id, :action/type, :action/target, :action/requester,
   :action/status :pending, :action/created-at, plus optional
   :action/justification and :action/parameters."
  control/create-control-action)

(def authorize-action
  "Check RBAC authorization for a control action against role
   definitions. Returns {:authorized? true :reason string} when the
   role permits the action on the target category, else {:authorized?
   false :reason string :anomaly map} (unknown role / unknown target
   type / forbidden action)."
  control/authorize-action)

(def execute-control-action!
  "Authorize then execute a control action. Emits
   :control-action/requested before and :control-action/executed after.
   On RBAC denial returns {:status :denied :reason string :anomaly map}
   and runs no execution-fn. On authorization, runs execution-fn and
   returns its result wrapped via response/success, or
   response/failure on a thrown exception."
  control/execute-control-action!)

(def requires-approval?
  "Return true when the given action-type keyword requires multi-party
   approval (:gate-override or :budget-escalation), else false."
  control/requires-approval?)

(def execute-control-action-with-approval!
  "Execute a control action, gating on approval first. When the action
   type requires approval, creates an approval request, emits
   :approval/requested, and returns {:status :awaiting-approval
   :approval/id ... :approval/required-signers ... :approval/quorum
   ...}. Otherwise delegates to execute-control-action! and returns its
   result map. approval-opts: :required-signers, :quorum,
   :approval-manager."
  control/execute-control-action-with-approval!)
