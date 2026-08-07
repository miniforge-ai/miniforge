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
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now fixture/now)

(def ^{:stratum 0} later fixture/later)

(def ^{:stratum 0} much-later fixture/much-later)

(def ^{:stratum 0} tmp-dir fixture/tmp-dir)

(def ^{:stratum 0} merge-grant fixture/merge-grant)

(def ^{:stratum 0} propose-merge! fixture/propose-merge!)

(def ^{:stratum 0} record-success! fixture/record-success!)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} interrupted-effect-is-unknown-not-failed-test
  ;; The state that earns this component its keep. Claiming failure for
  ;; a merge that actually landed is as wrong as claiming success.
  (let [dir (tmp-dir)
        g (merge-grant)
        t (propose-merge! dir g)
        committed (fx/commit! dir t g {} now
                              (fn [] (throw (ex-info "connection reset" {}))))]
    (is (= :unknown-outcome (:effect/state committed)))
    (is (not= :failed (:effect/state committed))
        "a throw means we do not know, not that it did not happen")
    (is (= "connection reset" (:effect/failure committed)))
    (testing "and the unknown state is durable, so a restart can find it"
      (is (= :unknown-outcome (:effect/state (fx/read-record dir (:effect/id t))))))))

(deftest ^{:stratum 1} definite-outcomes-are-recorded-as-such-test
  (let [dir (tmp-dir)
        g (merge-grant)]
    (testing "a definite success is :succeeded and carries the observation"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now
                             (fn [] {:effect/outcome :succeeded
                                     :effect/observed {:merge/sha "abc123"}}))]
        (is (= :succeeded (:effect/state done)))
        (is (= {:merge/sha "abc123"} (:effect/observed done)))))

    (testing "a definite failure is :failed — the effect fn knows it did not happen"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now
                             (fn [] {:effect/outcome :failed
                                     :effect/failure "PR already closed"}))]
        (is (= :failed (:effect/state done)))
        (is (= "PR already closed" (:effect/failure done)))))

    (testing "an unreadable report is unknown, not success"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now (fn [] {:whatever true}))]
        (is (= :unknown-outcome (:effect/state done))
            "an effect fn that answered in a shape we cannot read told us nothing")))

    (testing "a nil report is unknown, not success"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now (constantly nil))]
        (is (= :unknown-outcome (:effect/state done)))))))

(deftest ^{:stratum 1} commit-rechecks-the-grant-test
  ;; Ariadne 2b Group 4: a constraint checked only at decide() is a check
  ;; against stale state.
  (testing "a grant revoked after the proposal fails the commit, and the effect never fires"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          revoked (grant/revoke g :breach/cost-exceeded now)
          fired (atom false)
          done (fx/commit! dir t revoked {} now
                           (partial record-success! fired))]
      (is (= :failed (:effect/state done)))
      (is (not @fired) "the effect MUST NOT fire when the re-check refuses")
      (is (re-find #"inactive" (:effect/failure done)))))

  (testing "a grant that expired between propose and commit fails the commit"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          done (fx/commit! dir t g {} much-later
                           (partial record-success! fired))]
      (is (= :failed (:effect/state done)))
      (is (not @fired))))

  (testing "a breached ceiling fails the commit"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          done (fx/commit! dir t g {:usage/count 99} now
                           (partial record-success! fired))]
      (is (= :failed (:effect/state done)))
      (is (not @fired))))

  (testing "no grant at all is refused as a mismatch, and the record is untouched"
    ;; Distinct from a revoked-or-expired grant above: passing nil when
    ;; the record NAMES a grant is a caller supplying the wrong argument,
    ;; not the authority lapsing. Marking the record :failed for that
    ;; would blame the transaction for the caller's mistake.
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          result (fx/commit! dir t nil {} now
                             (partial record-success! fired))]
      (is (anomaly/anomaly? result))
      (is (not @fired))
      (is (= :proposed (:effect/state (fx/read-record dir (:effect/id t))))
          "the durable record is left as it was"))))

(deftest ^{:stratum 1} only-a-proposed-record-may-be-committed-test
  ;; The accident this component exists to prevent: committing a record
  ;; that has already moved on re-runs an irreversible effect. A second
  ;; merge. A second deploy.
  (let [dir (tmp-dir)
        g (merge-grant)]
    (testing "falsey proposals retain the empty-map default"
      (doseq [proposal [nil false]]
        (is (= {} (:effect/proposal
                   (fx/propose! dir {:effect-class :effect/merge
                                     :grant-id (:grant/id g)
                                     :proposal proposal}
                                now))))))
    (doseq [state [:committing :succeeded :failed :unknown-outcome :reconciled]]
      (let [t (assoc (propose-merge! dir g) :effect/state state)
            fired (atom false)
            result (fx/commit! dir t g {} now
                               (partial record-success! fired))]
        (is (anomaly/anomaly? result) (str "commit from " state " must refuse"))
        (is (= :conflict (:anomaly/type result)) (str state))
        (is (not @fired)
            (str "the effect MUST NOT re-fire from " state))))
    (testing "a :proposed record still commits normally"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now (fn [] {:effect/outcome :succeeded}))]
        (is (= :succeeded (:effect/state done)))))))

(deftest ^{:stratum 1} commit-must-use-the-grant-the-proposal-named-test
  ;; The re-check is only worth anything if it re-checks the SAME grant.
  ;; Otherwise: propose under a narrow grant, commit under a broad one,
  ;; and the durable record attests to authority that was never used.
  (let [dir (tmp-dir)
        recorded (merge-grant)
        t (propose-merge! dir recorded)]

    (testing "a different grant is refused, even a valid and broader one"
      (let [other (merge-grant {:constraints {}})
            fired (atom false)
            result (fx/commit! dir t other {} now
                               (partial record-success! fired))]
        (is (anomaly/anomaly? result))
        (is (= :conflict (:anomaly/type result)))
        (is (not @fired) "substituting a broader grant must not fire the effect")))

    (testing "a grant for a different effect class is refused"
      (let [deploy-grant (grant/issue {:principal "agent:implementer"
                                       :effect-class :effect/deploy
                                       :scope {}
                                       :constraints {}
                                       :delegable? false
                                       :expires-at later}
                                      now)
            mismatched (assoc t :effect/grant-id (:grant/id deploy-grant))
            fired (atom false)
            result (fx/commit! dir mismatched deploy-grant {} now
                               (partial record-success! fired))]
        (is (anomaly/anomaly? result))
        (is (not @fired) "a merge proposal must not be authorized by a deploy grant")))

    (testing "the recorded grant still commits"
      (let [done (fx/commit! dir t recorded {} now (fn [] {:effect/outcome :succeeded}))]
        (is (= :succeeded (:effect/state done)))))))

(deftest ^{:stratum 1} commit-enforces-grant-scope-test
  (testing "durable proposal scope overrides a caller-supplied scope"
    (let [dir (tmp-dir)
          g (merge-grant {:scope (assoc fixture/merge-proposal :pr/number 43)})
          t (propose-merge! dir g)
          fired (atom false)
          usage {:effect/scope (:grant/scope g)}
          result (fx/commit! dir t g usage now
                             (partial record-success! fired))]
      (is (= :failed (:effect/state result)))
      (is (re-find #"scope-mismatch" (:effect/failure result)))
      (is (not @fired)))))
