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
(ns ai.miniforge.tenancy.acting
  "The acting context — who a run is acting for (Ariadne step 3b, §2-§4).

   3a answers 'who is the operator'. This answers the question every
   record-birth site actually asks: 'on whose behalf is THIS running'.
   Those differ the moment a run spawns an agent, because the tenant
   stays the same and the principal does not.

   WHY IDS AND NOT THE WHOLE IDENTITY. The acting context rides in the
   execution context, which is checkpointed to disk and restored on
   resume. Carrying whole `Tenant` and `Principal` maps would mean a
   resumed run replays a display name captured months ago, and any
   correction to that name would silently not apply. Ids are the stable
   half; the records they point at are looked up when they are needed.

   ESTABLISHED-AT IS NOT CREATED-AT. `:tenant/created-at` is when the
   tenant came to exist. `:acting/established-at` is when THIS run
   resolved it. On resume the second one is deliberately preserved from
   the snapshot rather than re-stamped — the run is still acting under
   the authority it was started with, and re-stamping would make a
   resumed run look freshly authorized."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.tenancy.ids :as ids]
   [ai.miniforge.tenancy.instant :as instant]
   [ai.miniforge.tenancy.schema :as schema]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} no-acting
  [detail]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.tenancy/no-acting-context
                       detail
                       {}))

(def ^{:stratum 0} ActingContext
  "CLOSED. The two ids that answer 'on whose behalf', and when that was
   settled.

   Both ids are required. A tenant without a principal cannot say who
   did it; a principal without a tenant cannot say who owns the result.

   `:acting/established-at` is an ISO-8601 STRING, not an `inst?`. The
   acting context is written into the workflow machine snapshot, and
   that snapshot puts every value through `coerce/stringify-instants` —
   so an `inst?` here is an `Instant` going in and a `String` coming
   back, and the context would fail its own validation on resume. One
   canonical representation that survives the round trip unchanged is
   cheaper than a coercion at every boundary that reads it."
  [:map {:closed true}
   [:acting/tenant-id :uuid]
   [:acting/principal-id :uuid]
   [:acting/established-at [:fn instant/iso-instant?]]])

(defn ^{:stratum 0} establish
  "Reduce a resolved `Identity` to the acting context a run carries.

   Called ONCE per boundary. Downstream code reads what this produced;
   it does not call the resolver again. Two resolutions are two answers
   about who acted, which is worse than none."
  [identity now]
  {:acting/tenant-id (get-in identity [:identity/tenant :tenant/id])
   :acting/principal-id (get-in identity [:identity/principal :principal/id])
   :acting/established-at (instant/->iso now)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} agent-principal
  "The principal a spawned agent acts as.

   The tenant is inherited: an agent owns nothing, so there is nothing
   for it to own things AS. The principal is its own, so 'an agent
   instance did it' and 'the operator did it' remain distinguishable
   after the fact.

   This is the representational half of fence-not-restrain. The inner
   agent acts under a named identity inside the boundary rather than
   under the ambient authority of the process, which is what makes
   revoking the lending tenant's grant end the agent's authority too.

   Returns an anomaly rather than a Principal-shaped map when the
   derivation does not validate — a blank agent name, or an acting
   context with no tenant. This is public API, and a caller that has to
   remember to validate what it was handed is a caller that eventually
   forgets. Refusing here is the same rule the rest of this component
   follows."
  [acting agent-name]
  (let [principal {:principal/id (ids/stable-id "agent-principal"
                                                (str (:acting/tenant-id acting)
                                                     "/" agent-name))
                   :principal/tenant-id (:acting/tenant-id acting)
                   :principal/kind :agent-instance
                   :principal/display-name agent-name}]
    (if (m/validate schema/Principal principal)
      principal
      (no-acting (str "cannot derive a valid agent principal for agent name "
                      (pr-str agent-name))))))

(defn ^{:stratum 1} valid?
  [x]
  (m/validate ActingContext x))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} require-acting
  "Return the acting context held by `carrier`, or an anomaly.

   Never substitutes a default and never returns nil. A record created
   under a fabricated tenant is indistinguishable, later, from one
   created under a real one, and the audit trail cannot be repaired
   after the fact — so the failure belongs here, where it is still a
   recoverable error."
  [carrier key]
  (let [acting (get carrier key)]
    (cond
      (nil? acting)
      (no-acting (str "no acting context at " key "; refusing to proceed unowned"))

      (not (valid? acting))
      (no-acting (str "acting context at " key " is not a valid acting context"))

      :else acting)))

(defn ^{:stratum 2} for-agent
  "The acting context a spawned agent runs under.

   Same tenant, new principal, and the establishing instant carried
   forward from the spawning run — the agent's authority is the run's
   authority, not a fresh grant of its own."
  [acting agent-name]
  (if-not (valid? acting)
    (no-acting "cannot spawn an agent from an invalid acting context")
    (let [principal (agent-principal acting agent-name)]
      (if (anomaly/anomaly? principal)
        principal
        {:acting/tenant-id (:acting/tenant-id acting)
         :acting/principal-id (:principal/id principal)
         :acting/established-at (:acting/established-at acting)}))))
