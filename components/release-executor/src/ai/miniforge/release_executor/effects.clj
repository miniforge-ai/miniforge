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
(ns ai.miniforge.release-executor.effects
  "The release's push and PR creation as effect transactions
   (effect-transaction, Ariadne 2c) under fixed keys, so a retried or
   resumed release finds what an earlier attempt did, including an effect
   it never recorded because the process died while it ran.

   An effect's key is a name-based UUID of [class origin task subject
   attempt] (the pattern in phase-opsv/pr-model): `origin` is the run's
   lineage, `subject` what the effect acts on (the commit pushed, the
   branch a PR is opened from), and `attempt` counts the definite failures
   before it. A record left mid-effect (:committing or :unknown-outcome)
   is reconciled by asking the remote; a definite failure moves on to the
   next attempt's key.

   Authority is :authority/unenforced (recorded as :effect/authority
   :unenforced): nothing issues release grants yet."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.effect-transaction.interface :as fx])
  (:import
   [java.nio.charset StandardCharsets]
   [java.time Instant]
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} push-class
  "Effect class of a release push."
  :effect/push)

(def ^{:stratum 0} pr-create-class
  "Effect class of opening the release PR."
  :effect/pr-create)

(defn ^{:stratum 0} effect-id
  "Fixed key of one release effect (see the namespace doc)."
  [effect-class origin task subject attempt]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [effect-class (str origin) (some-> task str) (str subject) attempt])
              StandardCharsets/UTF_8)))

(defn ^{:stratum 0} store-dir
  "Where release effect records live: the context's :effect-store-dir, else
   `effects` under the miniforge home (where merge records live too)."
  [context]
  (or (:effect-store-dir context)
      (str (config/miniforge-home) "/effects")))

(defn- ^{:stratum 0} done?
  "The effect happened: it reported success, or the remote confirmed it."
  [t]
  (or (= :succeeded (:effect/state t))
      (and (= :reconciled (:effect/state t)) (true? (:effect/matched? t)))))

(defn- ^{:stratum 0} spent?
  "The effect definitely did not happen under this key: it failed, or the
   remote showed no trace of it. A retry takes the next attempt's key."
  [t]
  (or (= :failed (:effect/state t))
      (and (= :reconciled (:effect/state t)) (false? (:effect/matched? t)))))

(defn- ^{:stratum 0} settle!
  "Drive a record towards a known outcome: commit a proposal (running
   `effect-fn`), or reconcile a record left mid-effect with `probe-fn`."
  [dir t effect-fn probe-fn]
  (case (:effect/state t)
    :proposed (fx/commit! dir t :authority/unenforced {} (Instant/now) effect-fn)
    (:committing :unknown-outcome) (fx/reconcile! dir t probe-fn (Instant/now))
    t))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pending
  "The latest record of `key` (a map of :effect-class :origin :task
   :subject) when it is still mid-effect, else nil."
  [dir key]
  (loop [attempt 0]
    (let [t (fx/read-record dir (effect-id (:effect-class key) (:origin key) (:task key)
                                           (:subject key) attempt))]
      (cond
        (or (nil? t) (anomaly/anomaly? t)) nil
        (spent? t) (recur (inc attempt))
        (#{:committing :unknown-outcome} (:effect/state t)) t
        :else nil))))

(defn ^{:stratum 1} transact!
  "Run the effect `key` names at most once. `key` is a map of
   :effect-class :origin :task :subject; `proposal` is recorded on a new
   record. `effect-fn` (no args) performs the effect and reports
   {:effect/outcome :succeeded|:failed|:accepted ...}; `probe-fn` (the
   record) asks the remote and answers {:effect/observed m :effect/matched?
   bool}, or nil when the remote did not answer.

   Returns {:outcome :done :observed m :ran? bool}, {:outcome :failed
   :failure s :observed m}, or {:outcome :unresolved :detail x} when the
   outcome is unknown (the effect threw, or the remote did not answer)."
  [dir key proposal effect-fn probe-fn]
  (loop [attempt 0]
    (let [id (effect-id (:effect-class key) (:origin key) (:task key) (:subject key) attempt)
          found (fx/read-record dir id)
          t (if (nil? found)
              (fx/propose! dir {:effect-id id :effect-class (:effect-class key)
                                :grant-id nil :proposal proposal}
                           (Instant/now))
              found)]
      (cond
        (anomaly/anomaly? t) {:outcome :unresolved :detail t}
        (spent? t)           (recur (inc attempt))
        (done? t)            {:outcome :done :observed (:effect/observed t) :ran? false}
        :else
        (let [settled (settle! dir t effect-fn probe-fn)]
          (cond
            (anomaly/anomaly? settled) {:outcome :unresolved :detail settled}
            (done? settled)            {:outcome :done :observed (:effect/observed settled)
                                        :ran? (= :proposed (:effect/state t))}
            (= :reconciled (:effect/state settled)) (recur (inc attempt))
            (= :failed (:effect/state settled))     {:outcome :failed
                                                     :failure (:effect/failure settled)
                                                     :observed (:effect/observed settled)}
            :else {:outcome :unresolved :detail (select-keys settled [:effect/id :effect/state
                                                                      :effect/failure])}))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} settle-pending!
  "Reconcile the record of `key` left mid-effect, if there is one, with
   `probe-fn`. For a caller that found the effect's result without the
   record (a PR the lookup lists), so the record does not stay unsettled."
  [dir key probe-fn]
  (when-let [t (pending dir key)]
    (fx/reconcile! dir t probe-fn (Instant/now))))
