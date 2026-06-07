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

(ns ai.miniforge.event-stream.interface.listeners
  "Listener management API for the event stream."
  (:require
   [ai.miniforge.event-stream.listeners :as listeners]))

;------------------------------------------------------------------------------ Layer 0
;; Listener management

(def register-listener!
  "Register a listener on the stream with a capability level (:observe,
   :advise, or :control), wrapping its callback with capability-based
   and user filters and emitting :listener/attached. Returns the new
   listener-id (UUID). Throws an :anomalies/incorrect anomaly when the
   capability is invalid. listener-spec keys: :listener/type,
   :listener/capability, :listener/identity, :listener/filters,
   :listener/callback, :listener/options."
  listeners/register-listener!)

(def deregister-listener!
  "Unsubscribe a listener by id, drop its metadata, and emit
   :listener/detached. Returns true when the listener existed, or nil
   when no listener matched the id."
  listeners/deregister-listener!)

(def list-listeners
  "Return a sequence of registered listener metadata maps for the
   stream (empty when none registered)."
  listeners/list-listeners)

(def submit-annotation!
  "Submit an advisory annotation from a listener; requires :advise or
   :control capability. Emits and returns the :annotation/created event
   map. Throws an :anomalies/not-found anomaly when the listener is
   unknown, or :anomalies/forbidden when its capability is
   insufficient. annotation keys: :annotation/type, :annotation/content,
   :annotation/workflow-id."
  listeners/submit-annotation!)
