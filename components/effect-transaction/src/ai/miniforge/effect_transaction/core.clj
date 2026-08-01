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
(ns ai.miniforge.effect-transaction.core
  "propose -> commit -> reconcile for irreversible effects (Ariadne 2c).

   This GENERALIZES a pattern miniforge already runs rather than
   inventing one. The operator intervention path is already transacted:
   `operator_requests.clj` lands the request with ATOMIC_MOVE and
   refuses to report success for a gesture that did not reach disk, and
   `operator/application.clj` advances to `:dispatched` BEFORE firing
   the effect, then verifies a `{:observed :expected}` readback. Merge,
   deploy, and spend get the same treatment here.

   The judgment that shapes everything below: an effect that THREW is
   `:unknown-outcome`, not `:failed`. A timeout talking to GitHub may
   well have merged the PR. Recording failure for an effect that landed
   is exactly as wrong as recording success for one that did not, and
   both are worse than admitting we do not yet know."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.schema :as schema]
   [ai.miniforge.effect-transaction.store :as store]
   [ai.miniforge.execution-grant.interface :as grant]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Validation + outcome reading
(defn ^{:stratum 0} valid?
  "True when `t` satisfies the closed EffectTransaction schema."
  [t]
  (m/validate schema/EffectTransaction t))

(defn- ^{:stratum 0} invalid
  [message t]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.effect-transaction/invalid
                       message
                       {:explain (me/humanize (m/explain schema/EffectTransaction t))}))

(defn- ^{:stratum 0} wrong-state
  "The record is not in a state this operation applies to — a caller
   error about lifecycle position, not permission and not a transient
   fault. `:conflict` is what routes that correctly."
  [message data]
  (anomaly/sub-anomaly :conflict
                       :anomalies.effect-transaction/wrong-state
                       message
                       data))

(defn- ^{:stratum 0} unresolved
  "The external system did not answer, so the record stays as it is.
   `:unavailable` because this is TRANSIENT and the right response is to
   ask again later — classifying it as a caller error would send it to
   handling that never retries."
  [message data]
  (anomaly/sub-anomaly :unavailable
                       :anomalies.effect-transaction/unresolved
                       message
                       data))

(defn- ^{:stratum 0} outcome-of
  "Read an effect-fn's report. Anything this does not recognize is
   `:unknown-outcome` — an effect fn that answered in a shape we cannot
   read has told us nothing, and 'nothing' is not 'it failed'."
  [reported]
  (let [o (:effect/outcome reported)]
    (if (contains? #{:succeeded :failed} o) o :unknown-outcome)))

;------------------------------------------------------------------------------ Layer 1

;; Durable state advance
(defn- ^{:stratum 1} advance!
  "Apply `changes` to `t`, stamp `:effect/updated-at`, and persist
   atomically. Returns the new record, or an `:invalid-input` anomaly
   when the change would produce a record that does not validate — a
   record we cannot describe is one we cannot reconcile, so it never
   reaches disk."
  [dir t changes ^Instant now]
  (let [t' (assoc (merge t changes) :effect/updated-at now)]
    (if (valid? t')
      (do (store/write! dir t') t')
      (invalid "EffectTransaction change failed validation" t'))))

;; The transaction
(defn ^{:stratum 1} propose!
  "Record the intent to perform an irreversible effect, durably, BEFORE
   anything happens. Returns the `:proposed` record.

   A write failure propagates rather than returning a soft error: if we
   cannot record that we are about to do something irreversible, the
   caller must not do it.

   The 1-arity convenience form REQUIRES `:dir`. A nil directory would
   land records in the process working directory instead of the store —
   the durability component silently writing the audit trail somewhere
   nobody looks is the worst failure it could have, so it is refused
   rather than defaulted."
  ([{:keys [dir] :as opts}]
   (if (nil? dir)
     (anomaly/sub-anomaly :invalid-input
                          :anomalies.effect-transaction/no-store-dir
                          "propose! requires :dir — refusing to write records to the process working directory"
                          {})
     (propose! dir opts (Instant/now))))
  ([dir {:keys [effect-class grant-id envelope-id proposal]} ^Instant now]
   (let [t {:effect/id (random-uuid)
            :effect/class effect-class
            :effect/grant-id grant-id
            :effect/envelope-id envelope-id
            :effect/proposal (or proposal {})
            :effect/state :proposed
            :effect/at now
            :effect/updated-at now}]
     (if (valid? t)
       (do (store/write! dir t) t)
       (invalid "EffectTransaction inputs failed validation" t)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit!
  "Re-check the grant, mark the record `:committing`, run `effect-fn`,
   and record what it reported.

   The re-check is the point (Ariadne 2b Group 4): a grant that was live
   at decide() and revoked or expired before now fails HERE, and the
   effect never fires. `effect-fn` is a thunk returning
   `{:effect/outcome :succeeded|:failed, :effect/observed ...}`.

   Outcome mapping, and the reason for it:
     returns :succeeded  -> :succeeded
     returns :failed     -> :failed        (it knows the effect did not happen)
     THROWS              -> :unknown-outcome
     anything else       -> :unknown-outcome

   A throw is not a failure. The call may have reached GitHub and come
   back as a timeout; the record stays honestly unknown until
   `reconcile!` asks.

   The supplied grant must be THE grant the proposal named, and its
   effect class must match the proposed effect. Re-checking a different
   grant would authorize the effect against authority the proposal never
   claimed.

   Only a `:proposed` record may be committed. Committing one that has
   already moved on would re-run an irreversible effect — a second
   merge, a second deploy — which is the exact class of accident this
   component exists to make impossible. Refused, and the effect does not
   fire.

   JVM `Error`s are NOT caught. An OutOfMemoryError is not an effect
   outcome, and the record is already `:committing` on disk before
   `effect-fn` runs, so letting it propagate leaves precisely the state
   reconciliation is built for."
  [dir t grant-record usage ^Instant now effect-fn]
  (let [auth (grant/authorize grant-record usage now)]
    (cond
      (not= :proposed (:effect/state t))
      (wrong-state "only a :proposed record may be committed"
                   {:effect/id (:effect/id t) :effect/state (:effect/state t)})

      ;; The record NAMES the grant that authorized it. Re-checking some
      ;; other grant would authorize the effect against authority the
      ;; proposal never claimed — propose under a narrow grant, commit
      ;; under a broad one, and the audit trail records a lie. The
      ;; re-check is only worth anything if it re-checks the SAME grant.
      (not= (:effect/grant-id t) (:grant/id grant-record))
      (wrong-state "grant does not match the one recorded on the proposal"
                   {:effect/id (:effect/id t)
                    :effect/grant-id (:effect/grant-id t)
                    :grant/id (:grant/id grant-record)})

      ;; Likewise the class: a merge grant must not authorize a deploy.
      (not= (:effect/class t) (:grant/effect-class grant-record))
      (wrong-state "grant effect-class does not match the proposed effect"
                   {:effect/id (:effect/id t)
                    :effect/class (:effect/class t)
                    :grant/effect-class (:grant/effect-class grant-record)})

      (not (grant/authorized? auth))
      (advance! dir t {:effect/state :failed
                       :effect/failure (str "grant re-check failed at commit: "
                                            (name (:grant/outcome auth)))}
                now)

      :else
      (let [committing (advance! dir t {:effect/state :committing} now)]
        (if (anomaly/anomaly? committing)
          committing
          (let [reported (try
                           {:ok (effect-fn)}
                           (catch Exception e
                             {:threw (or (ex-message e) (str (class e)))}))]
            (if (contains? reported :threw)
              (advance! dir committing
                        {:effect/state :unknown-outcome
                         :effect/failure (:threw reported)}
                        now)
              (let [r (:ok reported)]
                (advance! dir committing
                          (cond-> {:effect/state (outcome-of r)}
                            (contains? r :effect/observed)
                            (assoc :effect/observed (:effect/observed r))
                            (:effect/failure r)
                            (assoc :effect/failure (:effect/failure r)))
                          now)))))))))

(defn ^{:stratum 2} reconcile!
  "Settle a record whose true outcome is not known locally by ASKING the
   external system. `probe-fn` takes the record and returns
   `{:effect/observed ... :effect/matched? bool}`.

   The answer, not the local guess, becomes `:effect/observed`, and a
   mismatch is recorded rather than smoothed over.

   A probe that throws or answers in an unreadable shape leaves the
   record where it is. Marking it `:reconciled` on a failed probe would
   assert we found out when we did not, and would stop anything from
   ever asking again.

   An answer that omits `:effect/matched?` records `false`: an
   unconfirmed match is treated as a mismatch, because an unflagged
   disagreement is worse than a flagged one.

   JVM `Error`s are NOT caught here either — a probe that dies of
   OutOfMemory has not told us the effect is unresolvable, it has told
   us the process is."
  [dir t probe-fn ^Instant now]
  (if-not (contains? schema/reconcilable-states (:effect/state t))
    (wrong-state "only :committing or :unknown-outcome records are reconcilable"
                 {:effect/id (:effect/id t) :effect/state (:effect/state t)})
    ;; The probe's answer is wrapped rather than probed for a marker key:
    ;; the probe returns caller-shaped data, so any in-band sentinel is a
    ;; value it could legitimately carry. The wrapper map is ours.
    (let [probed (try {:ok (probe-fn t)}
                      (catch Exception e {:threw (or (ex-message e) (str (class e)))}))
          answer (:ok probed)]
      (if (or (contains? probed :threw)
              (not (map? answer))
              (not (contains? answer :effect/observed)))
        (unresolved "reconciliation probe did not answer; record stays unresolved"
                    (cond-> {:effect/id (:effect/id t) :effect/state (:effect/state t)}
                      (contains? probed :threw)
                      (assoc :probe/error (:threw probed))))
        (advance! dir t
                  {:effect/state :reconciled
                   :effect/observed (:effect/observed answer)
                   :effect/matched? (boolean (:effect/matched? answer))}
                  now)))))
