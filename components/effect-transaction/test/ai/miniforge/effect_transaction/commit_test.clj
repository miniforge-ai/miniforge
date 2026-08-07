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
(ns ai.miniforge.effect-transaction.commit-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.fixtures :as fixture]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now fixture/now)

(def ^{:stratum 0} later fixture/later)

(def ^{:stratum 0} much-later fixture/much-later)

(def ^{:stratum 0} tmp-dir fixture/tmp-dir)

(def ^{:stratum 0} merge-grant fixture/merge-grant)

(def ^{:stratum 0} propose-merge! fixture/propose-merge!)

(def ^{:stratum 0} merge-case! fixture/merge-case!)

(def ^{:stratum 0} record-success! fixture/record-success!)

(def ^{:stratum 0} succeed! fixture/succeed!)

(def ^{:stratum 0} throw-exception! fixture/throw-exception!)

(def ^{:stratum 0} count-success! fixture/count-success!)

(def ^{:stratum 0} block-and-count-success! fixture/block-and-count-success!)

(def ^{:stratum 0} concurrency-timeout-ms fixture/concurrency-timeout-ms)

(def ^{:stratum 0} no-usage fixture/no-usage)

(def ^{:stratum 0} claimed-usage fixture/claimed-usage)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} interrupted-effect-is-unknown-not-failed-test
  ;; The state that earns this component its keep. Claiming failure for
  ;; a merge that actually landed is as wrong as claiming success.
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        committed (fx/commit! dir t g no-usage now
                              (partial throw-exception! "connection reset"))]
    (is (= :unknown-outcome (:effect/state committed)))
    (is (not= :failed (:effect/state committed))
        "a throw means we do not know, not that it did not happen")
    (is (= "connection reset" (:effect/failure committed)))
    (is (= :unknown-outcome
           (:effect/state (fx/read-record dir (:effect/id t))))
        "the unknown state is durable for a fresh reader")))

(deftest ^{:stratum 1} definite-success-records-observation-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now
                         (fn [] {:effect/outcome :succeeded
                                 :effect/observed {:merge/sha "abc123"}}))]
    (is (= :succeeded (:effect/state done)))
    (is (= {:merge/sha "abc123"} (:effect/observed done)))))

(deftest ^{:stratum 1} definite-failure-records-reason-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now
                         (fn [] {:effect/outcome :failed
                                 :effect/failure "PR already closed"}))]
    (is (= :failed (:effect/state done)))
    (is (= "PR already closed" (:effect/failure done)))))

(deftest ^{:stratum 1} unreadable-effect-report-is-unknown-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now (fn [] {:whatever true}))]
    (is (= :unknown-outcome (:effect/state done))
        "an unreadable report supplies no outcome knowledge")))

(deftest ^{:stratum 1} nil-effect-report-is-unknown-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now (constantly nil))]
    (is (= :unknown-outcome (:effect/state done)))))

(deftest ^{:stratum 1} revoked-grant-recheck-denies-effect-test
  ;; A constraint checked only at decide time is a check against stale state.
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        revoked (grant/revoke g :breach/cost-exceeded now)
        fired (atom false)
        done (fx/commit! dir t revoked no-usage now
                         (partial record-success! fired))]
    (is (= :failed (:effect/state done)))
    (is (not @fired) "the effect MUST NOT fire when the re-check refuses")
    (is (re-find #"inactive" (:effect/failure done)))))

(deftest ^{:stratum 1} expired-grant-recheck-denies-effect-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        fired (atom false)
        done (fx/commit! dir t g no-usage much-later
                         (partial record-success! fired))]
    (is (= :failed (:effect/state done)))
    (is (not @fired))))

(deftest ^{:stratum 1} caller-cannot-fabricate-breached-usage-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        fired (atom false)
        done (fx/commit! dir t g (claimed-usage 99) now
                         (partial record-success! fired))]
    (is (= :succeeded (:effect/state done)))
    (is @fired)))

(deftest ^{:stratum 1} caller-zero-cannot-bypass-derived-count-test
  (let [dir (tmp-dir)
        {g :grant t :transaction}
        (merge-case! dir {:constraints {:constraint/max-count 0}})
        fired (atom false)
        done (fx/commit! dir t g (claimed-usage 0) now
                         (partial record-success! fired))]
    (is (= :failed (:effect/state done)))
    (is (not @fired))))

(deftest ^{:stratum 1} missing-grant-leaves-proposal-untouched-test
  ;; A missing argument is a caller conflict, not an authority lapse that
  ;; should permanently fail the durable transaction.
  (let [dir (tmp-dir)
        {t :transaction} (merge-case! dir)
        fired (atom false)
        result (fx/commit! dir t nil no-usage now
                           (partial record-success! fired))]
    (is (anomaly/anomaly? result))
    (is (not @fired))
    (is (= :proposed (:effect/state (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 1} missing-effect-id-is-invalid-input-test
  (let [dir (tmp-dir)
        fired (atom false)
        result (fx/commit! dir {} :authority/unenforced no-usage now
                           (partial record-success! fired))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (not @fired))
    (is (empty? (fx/list-records dir)))))

(deftest ^{:stratum 1} falsey-proposals-retain-empty-default-test
  (let [dir (tmp-dir)]
    (doseq [proposal [nil false]]
      (let [g (merge-grant)
            t (propose-merge! dir g proposal)]
        (is (= {} (:effect/proposal t)))))))

(deftest ^{:stratum 1} stale-proposal-replay-does-not-refire-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        calls (atom 0)
        effect! (partial count-success! calls)
        first-result (fx/commit! dir t g no-usage now effect!)
        replay-result (fx/commit! dir t g no-usage now effect!)]
    (is (= :succeeded (:effect/state first-result)))
    (is (anomaly/anomaly? replay-result))
    (is (= 1 @calls))))

(deftest ^{:stratum 1} caller-state-cannot-replace-durable-state-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        forged (assoc t :effect/state :succeeded)
        done (fx/commit! dir forged g no-usage now succeed!)]
    (is (= :succeeded (:effect/state done)))))

(deftest ^{:stratum 1} proposal-preserves-preallocated-id-without-replacement-test
  (let [dir (tmp-dir)
        g (merge-grant)
        id (get-in g [:grant/scope :effect/id])
        original (propose-merge! dir g)
        duplicate (propose-merge! dir g {:pr/number 99})]
    (is (= id (:effect/id original)))
    (is (anomaly/anomaly? duplicate))
    (is (= original (fx/read-record dir id)))))

(deftest ^{:stratum 1} substituted-broader-grant-is-refused-test
  ;; The re-check is only worth anything if it re-checks the SAME grant.
  ;; Otherwise: propose under a narrow grant, commit under a broad one,
  ;; and the durable record attests to authority that was never used.
  (let [dir (tmp-dir)
        {t :transaction} (merge-case! dir)
        other (merge-grant {:constraints {}})
        fired (atom false)
        result (fx/commit! dir t other no-usage now
                           (partial record-success! fired))]
    (is (anomaly/anomaly? result))
    (is (= :conflict (:anomaly/type result)))
    (is (not @fired) "substituting a broader grant must not fire the effect")))

(deftest ^{:stratum 1} different-effect-class-grant-is-refused-test
  (let [dir (tmp-dir)
        deploy-grant (merge-grant {:effect-class :effect/deploy})
        mismatched (propose-merge! dir deploy-grant)
        fired (atom false)
        result (fx/commit! dir mismatched deploy-grant no-usage now
                           (partial record-success! fired))]
    (is (anomaly/anomaly? result))
    (is (not @fired) "a merge proposal must not use a deploy grant")))

(deftest ^{:stratum 1} proposal-recorded-grant-commits-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now succeed!)]
    (is (= :succeeded (:effect/state done)))))

(deftest ^{:stratum 1} concurrent-commit-invokes-the-effect-once-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        calls (atom 0)
        entered (promise)
        release (promise)
        first-result (future
                       (fx/commit! dir t g no-usage now
                                   (partial block-and-count-success!
                                            calls entered release)))]
    (is (= true (deref entered concurrency-timeout-ms :timeout)))
    (let [second-result (fx/commit! dir t g no-usage now
                                    (partial count-success! calls))]
      (deliver release true)
      (is (= :succeeded
             (:effect/state (deref first-result concurrency-timeout-ms :timeout))))
      (is (anomaly/anomaly? second-result))
      (is (= 1 @calls)))))

(deftest ^{:stratum 1} process-failure-after-claim-leaves-committing-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)]
    (is (thrown? AssertionError
                 (fx/commit! dir t g no-usage now
                             (fn [] (throw (AssertionError.))))))
    (is (= :committing
           (:effect/state (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 1} durable-scope-overrides-caller-context-test
  (let [dir (tmp-dir)
        {g :grant t :transaction}
        (merge-case! dir {:scope (assoc fixture/merge-proposal :pr/number 43)})
        forged (assoc t :effect/proposal (:grant/scope g))
        fired (atom false)
        usage {:effect/scope (:grant/scope g)}
        result (fx/commit! dir forged g usage now
                           (partial record-success! fired))]
    (is (= :failed (:effect/state result)))
    (is (re-find #"scope-mismatch" (:effect/failure result)))
    (is (not @fired))))

(deftest ^{:stratum 1} grant-bound-to-another-effect-is-refused-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        other-effect-grant (assoc-in g [:grant/scope :effect/id] (random-uuid))
        fired (atom false)
        result (fx/commit! dir t other-effect-grant no-usage now
                           (partial record-success! fired))]
    (is (= :failed (:effect/state result)))
    (is (not @fired))))
