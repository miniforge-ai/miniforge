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
(ns ai.miniforge.execution-grant.lineage
  "Liveness over a grant's ancestry (Ariadne step 2a, §13.5).

   A grant is active only if IT is live and EVERY ancestor is live. That
   is the whole point of lineage: revoking a parent has to end what was
   cut from it, or a delegated inner orchestrator keeps its authority
   after you take the parent's away.

   Every unresolvable condition here fails CLOSED. An ancestor we cannot
   read is not an ancestor we may assume is fine — that assumption is
   the fail-open bug this architecture exists to make unrepresentable."
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Local liveness
(def ^{:stratum 0} max-lineage-depth
  "Depth ceiling for an ancestry walk. A well-formed chain is short;
   this bounds a malformed or hostile one so a cycle cannot hang the
   check. Reaching it fails closed."
  64)

(defn ^{:stratum 0} revoked?
  "True when this grant carries a revocation stamp. Says nothing about
   ancestors — see `active?`."
  [grant]
  (some? (:grant/revoked-at grant)))

(defn ^{:stratum 0} expired?
  "True when `grant` has passed its expiry as of `now`."
  [grant ^Instant now]
  (let [^Instant expires (:grant/expires-at grant)]
    (or (nil? expires)
        (.isAfter now expires))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} live?
  "Local liveness only: not revoked and not expired. A grant can be
   `live?` and still not `active?` when an ancestor was revoked."
  [grant now]
  (and (some? grant)
       (not (revoked? grant))
       (not (expired? grant now))))

;; Ancestry walk
(defn ^{:stratum 1} ancestry
  "Walk from `grant` up through `:grant/parent-id` using `lookup`
   (`id -> grant-or-nil`), returning the chain from `grant` upward.

   A `nil` element marks an UNRESOLVABLE ancestor — the walk records it
   rather than dropping it, so callers cannot mistake 'parent missing'
   for 'no parent'. A cycle or an over-deep chain terminates with a nil
   for the same reason: the caller must treat it as unverifiable."
  [grant lookup]
  (loop [current grant
         seen #{}
         acc []]
    (let [acc' (conj acc current)]
      (cond
        (nil? current) acc'
        (>= (count acc') max-lineage-depth) (conj acc' nil)
        :else
        (let [parent-id (:grant/parent-id current)]
          (cond
            (nil? parent-id) acc'
            (contains? seen parent-id) (conj acc' nil)
            :else (recur (lookup parent-id)
                         (conj seen parent-id)
                         acc')))))))

;------------------------------------------------------------------------------ Layer 2

;; The predicate
(defn ^{:stratum 2} active?
  "True when `grant` and every ancestor are live as of `now`.

   The 2-arity form is for ROOT grants only: a grant that names a parent
   cannot be verified without a `lookup`, so this returns false for it
   rather than reporting an unverified grant as active.

   The 3-arity form walks the chain via `lookup` (`id -> grant-or-nil`)
   and returns false if any link is revoked, expired, or unresolvable."
  ([grant now]
   (and (nil? (:grant/parent-id grant))
        (live? grant now)))
  ([grant now lookup]
   (let [chain (ancestry grant lookup)]
     (and (seq chain)
          (every? #(live? % now) chain)))))
