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

(ns ai.miniforge.connector.validation
  "Shared validation helpers for connector implementations.

   Every connector (`connector-jira`, `connector-gitlab`, `connector-github`,
   `connector-excel`, `connector-edgar`, `connector-file`, `connector-sarif`,
   `connector-pipeline-output`) repeats two patterns:

   1. **Handle lookup**:    `(or (get-handle h) (throw …))`
   2. **Auth validation**:  `(when-not (:success? (auth/validate-credential-ref a))
                              (throw …))`

   Both forms are validation/lookup helpers — exactly the shape the
   exceptions-as-data rule (foundations/005) targets. This namespace
   centralizes them, providing two variants for each:

   - **Anomaly-returning** (preferred): `require-handle`, `validate-auth`.
     Return the looked-up value or success on the happy path; return a
     canonical anomaly map on failure.
   - **Throwing** (deprecated, backward-compat): `require-handle!`,
     `validate-auth!`. Delegate to the anomaly variant and translate an
     anomaly result into an `ex-info` throw.

   Per-connector callsites still call the local thrower today; migration to
   the anomaly variant is per-connector follow-on work. Centralizing here
   removes the duplication and gives every connector the same retire-throw
   migration path."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector-auth.interface :as auth]
            [ai.miniforge.connector.handles :as handles]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle lookup

(defn require-handle
  "Look up handle state in `store`. Return the state on success; return a
   `:not-found` anomaly when the handle is unknown.

   - `store`  — handle registry atom (see [[handles/create]])
   - `handle` — opaque handle id
   - `opts`   — optional `{:message string, :connector keyword}`. The
                message defaults to a generic \"Handle not found\" string;
                the connector keyword (e.g. `:jira`, `:github`) is included
                in `:anomaly/data` for diagnostics."
  ([store handle]
   (require-handle store handle nil))
  ([store handle {:keys [message connector] :as _opts}]
   (if-let [state (handles/get-handle store handle)]
     state
     (anomaly/anomaly :not-found
                      (or message "Handle not found")
                      (cond-> {:handle handle}
                        connector (assoc :connector connector))))))

(defn require-handle!
  "Look up handle state in `store` or throw `ex-info` when absent.

   DEPRECATED: prefer [[require-handle]] in non-boundary code; it returns
   an anomaly map instead of throwing, so callers can fold the failure
   into a response chain. This thrower is retained as a drop-in for the
   per-connector helpers being migrated incrementally."
  {:deprecated "exceptions-as-data — prefer require-handle"}
  ([store handle]
   (require-handle! store handle nil))
  ([store handle {:keys [message] :as opts}]
   (let [result (require-handle store handle opts)]
     (if (anomaly/anomaly? result)
       (throw (ex-info (or message (:anomaly/message result))
                       (:anomaly/data result)))
       result))))

;;------------------------------------------------------------------------------ Layer 1
;; Auth validation

(defn- auth-success?
  "True when `result` is a credential-ref validation success map."
  [result]
  (true? (:success? result)))

(defn validate-auth
  "Validate a credential reference, if one is supplied. Return `nil` when
   `auth` is nil (no credentials required) or when validation succeeds;
   return an `:invalid-input` anomaly when validation fails.

   - `auth` — credential reference map, or nil
   - `opts` — optional `{:message string, :connector keyword}`. The
              connector keyword is included in `:anomaly/data` for
              diagnostics."
  ([auth]
   (validate-auth auth nil))
  ([auth {:keys [message connector] :as _opts}]
   (when auth
     (let [result (auth/validate-credential-ref auth)]
       (when-not (auth-success? result)
         (let [errors (:errors result)]
           (anomaly/anomaly :invalid-input
                            (or message "Credential validation failed")
                            (cond-> {:errors errors}
                              connector (assoc :connector connector)))))))))

(defn validate-auth!
  "Validate a credential reference, throwing `ex-info` on failure.

   DEPRECATED: prefer [[validate-auth]] in non-boundary code; it returns
   an anomaly map instead of throwing. This thrower is retained for the
   per-connector helpers that have not yet migrated."
  {:deprecated "exceptions-as-data — prefer validate-auth"}
  ([auth]
   (validate-auth! auth nil))
  ([auth {:keys [message] :as opts}]
   (let [result (validate-auth auth opts)]
     (when (anomaly/anomaly? result)
       (throw (ex-info (or message (:anomaly/message result))
                       (:anomaly/data result)))))))
