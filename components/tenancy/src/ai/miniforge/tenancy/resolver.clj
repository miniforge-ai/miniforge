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
(ns ai.miniforge.tenancy.resolver
  "Where an operator identity comes from (Ariadne step 3a).

   Today there is none. Single-operator use is implicit, and 'the
   process that is running' stands in for 'who asked'. This makes that
   substitution explicit and bounded rather than leaving it as an
   assumption every future call site would have to re-make.

   WHY THIS EXISTS AS ITS OWN THING. The step-2 wave specified the grant
   object, checking grants, transacting effects, migrating call sites,
   and revoking grants — and never specified who ISSUES one. That gap
   surfaced only at the call-site migration, where the spec as written
   would have denied every merge and deploy permanently. Ownership has
   the identical shape: a record cannot be born owned if nothing
   establishes an owner. So the source lands with the objects.

   THE SEAM. `resolve-operator` is one function with one return shape.
   A config-backed implementation is what exists now; a credential-backed
   one (the RFC puts credential->principal in the authn layer, and calls
   that layer thin) drops into the same seam later. Callers depend on
   the shape, not on where it came from — which is what keeps the real
   resolver from becoming a second migration through every record-birth
   site."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.tenancy.ids :as ids]
   [ai.miniforge.tenancy.schema :as schema]
   [clojure.string :as str]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} no-identity
  "No operator was configured. An expected state today, and the caller
   may reasonably carry on without one."
  [detail]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.tenancy/no-operator-identity
                       detail
                       {}))

(defn- ^{:stratum 0} bad-identity
  "An operator WAS configured and is wrong.

   A distinct subtype rather than a distinguishing message, because the
   difference is what callers must branch on: 'nobody set this up yet'
   is silence, and 'you set it up wrong' has to be visible or the
   configuration error is undetectable. Prose in a message cannot be
   branched on without matching strings."
  [detail]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.tenancy/invalid-operator-identity
                       detail
                       {}))

(defn ^{:stratum 0} operator-identity
  "Build an operator tenant and its human principal from a configured
   name. Pure — the instant is supplied so callers can test it."
  [operator-name ^Instant now]
  (let [tenant-id (ids/stable-id "tenant" operator-name)]
    {:identity/tenant {:tenant/id tenant-id
                       :tenant/kind :operator
                       :tenant/display-name operator-name
                       :tenant/created-at now}
     :identity/principal {:principal/id (ids/stable-id "principal" operator-name)
                          :principal/tenant-id tenant-id
                          :principal/kind :human
                          :principal/display-name operator-name}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-operator
  "Resolve who is operating, from configuration.

   Returns an `Identity`, or an `:invalid-input` anomaly when no operator
   is configured. It does NOT invent one.

   The refusal is the important half. A default tenant here would be
   indistinguishable, later, from a real operator who happened to be
   named 'default' — and every record created under it would carry an
   owner that looks observed and is actually fabricated. An error at the
   boundary is recoverable; a plausible wrong owner in the audit trail
   is not."
  ([config] (resolve-operator config (Instant/now)))
  ([config ^Instant now]
   (let [configured (get-in config [:tenancy :operator-name])
         ;; A STRING or nothing. Coercing with `str` would turn a config
         ;; typo — a map, a vector, a number — into a tenant display name
         ;; like "{:a 1}", and step 3c stamps that onto records as an
         ;; owner that looks observed and is really a mistyped key. That
         ;; is the same fabrication this function refuses elsewhere,
         ;; arriving by coercion instead of by default.
         operator-name (when (string? configured) (str/trim configured))]
     (if (str/blank? operator-name)
       ;; Most specific cause first — a blank string is a string, and
       ;; reporting a type error for it sends the reader to the wrong
       ;; field.
       (cond
         (nil? configured)
         (no-identity "no operator identity configured at [:tenancy :operator-name]; refusing to invent one")

         (not (string? configured))
         (bad-identity (str "operator identity at [:tenancy :operator-name] must be a string, got "
                            (.getSimpleName (class configured)) "; refusing to coerce one"))

         :else
         (bad-identity "operator identity at [:tenancy :operator-name] is blank; refusing to invent one"))
       (let [identity (operator-identity operator-name now)]
         (if (m/validate schema/Identity identity)
           identity
           (bad-identity "configured operator did not produce a valid identity")))))))
