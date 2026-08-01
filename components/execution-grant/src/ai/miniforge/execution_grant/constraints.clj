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
(ns ai.miniforge.execution-grant.constraints
  "The ONE check site for whether an effect is within its grant
   (Ariadne step 2b, §13.5).

   This is the representational fix for T3 contradiction 9. The 3h40m
   runaway happened because a ceiling was wired to one of two channels
   and the other went unmetered. Wiring each channel individually is
   the mistake; a ceiling belongs on the GRANT, and every path to an
   effect passes through `authorize` here.

   `authorize` is called TWICE per effect — once at decide() and again
   at commit (step 2c). Same function, different instants: a grant that
   was live at decide and revoked before commit fails the second call.
   That is the whole point of re-checking rather than trusting the
   first answer.

   Everything fails CLOSED. A nil grant, an inactive grant, and an
   unresolvable ancestry all deny; absence is not silence (§13.7)."
  (:require
   [ai.miniforge.execution-grant.lineage :as lineage]))

;------------------------------------------------------------------------------ Layer 0

;; Usage → ceiling correspondence
(def ^{:stratum 0} usage->constraint
  "Which ceiling bounds which observed quantity. Adding a usage axis
   without its ceiling here would silently make that axis unbounded, so
   the map is the single place the correspondence is stated."
  {:usage/cost-usd :constraint/max-cost-usd
   :usage/tokens :constraint/max-tokens
   :usage/count :constraint/max-count})

(def ^{:stratum 0} legacy-budget-aliases
  "The scattered budget key spellings found across the tree, mapped to
   their constraint axis. Three namespaces express one concept three
   ways today — `agent/role-defaults.edn` uses `{:tokens :cost-usd}`,
   `orchestrator` and `loop` use `{:max-tokens :max-cost-usd}`, and
   `workflow/validator` uses `{:budget/tokens :budget/cost-usd}`. That
   divergence is the evidence for moving ceilings onto grants; this map
   is the migration path, not a fourth spelling.

   Iteration and duration keys are deliberately ABSENT: `:iterations`
   still governs phase looping (not an irreversible effect), and the
   time ceiling is `:grant/expires-at`, not a constraint axis."
  {:tokens :constraint/max-tokens
   :max-tokens :constraint/max-tokens
   :budget/tokens :constraint/max-tokens
   :cost-usd :constraint/max-cost-usd
   :max-cost-usd :constraint/max-cost-usd
   :budget/cost-usd :constraint/max-cost-usd})

(defn ^{:stratum 0} authorized?
  "True when `authorize` returned `:authorized`."
  [result]
  (= :authorized (:grant/outcome result)))

;------------------------------------------------------------------------------ Layer 1

;; Breach detection
(defn ^{:stratum 1} breaches
  "Ceilings `usage` exceeds on `grant`, as a vector of
   `{:constraint/axis :constraint/limit :constraint/observed}`.

   An absent ceiling is unbounded and cannot be breached; an absent
   usage reading is not evidence of a breach. Only a reading strictly
   above a stated ceiling counts — a run that lands exactly ON its
   budget spent what it was given, not more.

   A reading that is present but NOT a number counts as a breach. It is
   not a pass (we cannot show it is under the ceiling) and it must not
   throw (a crash at the check site neither denies nor allows — it just
   takes the effect path down). Unverifiable is denied, same as
   everywhere else here."
  [grant usage]
  (into []
        (keep (fn [[usage-key axis]]
                (let [limit (get (:grant/constraints grant) axis)
                      observed (get usage usage-key)]
                  (when (and (some? limit)
                             (some? observed)
                             (or (not (number? observed))
                                 (> observed limit)))
                    {:constraint/axis axis
                     :constraint/limit limit
                     :constraint/observed observed}))))
        usage->constraint))

(defn ^{:stratum 1} budget->constraints
  "Translate a legacy budget map into `:grant/constraints`. Unknown keys
   are dropped rather than guessed at — a key this map does not know is
   a ceiling nobody has decided the meaning of, and inventing one would
   re-create the divergence this is meant to end."
  [budget]
  (into {}
        (keep (fn [[k v]]
                (when-let [axis (get legacy-budget-aliases k)]
                  (when (some? v) [axis v]))))
        budget))

;------------------------------------------------------------------------------ Layer 2

;; The check site
(defn ^{:stratum 2} authorize
  "Is this effect authorized by `grant`, given `usage`, as of `now`?

   Returns one of:
     {:grant/outcome :authorized :grant/id id}
     {:grant/outcome :absent}                        - no grant at all
     {:grant/outcome :inactive :grant/id id
      :grant/revocation-reason r}                    - revoked, expired,
                                                       or unverifiable
                                                       ancestry
     {:grant/outcome :exceeded :grant/id id
      :constraint/breaches [...]}                    - over a ceiling

   The 3-arity form is root-only, mirroring `lineage/active?`: a grant
   naming a parent it was given no way to resolve is NOT authorized,
   because an unverified grant must never read as a live one. Pass a
   `lookup` to authorize a delegated grant."
  ([grant usage now] (authorize grant usage now nil))
  ([grant usage now lookup]
   (if (nil? grant)
     {:grant/outcome :absent}
     (let [active? (if lookup
                     (lineage/active? grant now lookup)
                     (lineage/active? grant now))
           over (when active? (breaches grant usage))]
       (cond
         (not active?)
         {:grant/outcome :inactive
          :grant/id (:grant/id grant)
          :grant/revocation-reason (:grant/revocation-reason grant)}

         (seq over)
         {:grant/outcome :exceeded
          :grant/id (:grant/id grant)
          :constraint/breaches over}

         :else
         {:grant/outcome :authorized
          :grant/id (:grant/id grant)})))))
