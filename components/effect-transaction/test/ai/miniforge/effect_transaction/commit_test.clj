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
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-08-01T01:00:00Z"))

(def ^{:stratum 0} much-later (Instant/parse "2026-08-02T00:00:00Z"))

(defn ^{:stratum 0} tmp-dir
  "A fresh directory per test — records are files, so tests that shared
   one would see each other's."
  []
  (str (.toFile (Files/createTempDirectory "fx-test" (into-array FileAttribute [])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} merge-grant
  ([] (merge-grant {}))
  ([overrides]
   (grant/issue (merge {:principal "agent:implementer"
                        :effect-class :effect/merge
                        :scope {:repo "miniforge-ai/miniforge" :pr 42}
                        :constraints {:constraint/max-count 5}
                        :delegable? false
                        :expires-at later}
                       overrides)
                now)))

(defn ^{:stratum 1} propose-merge!
  [dir g]
  (fx/propose! dir
               {:effect-class :effect/merge
                :grant-id (:grant/id g)
                :envelope-id (random-uuid)
                :proposal {:pr/repo "miniforge-ai/miniforge" :pr/number 42}}
               now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} interrupted-effect-is-unknown-not-failed-test
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

(deftest ^{:stratum 2} definite-outcomes-are-recorded-as-such-test
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

(deftest ^{:stratum 2} commit-rechecks-the-grant-test
  ;; Ariadne 2b Group 4: a constraint checked only at decide() is a check
  ;; against stale state.
  (testing "a grant revoked after the proposal fails the commit, and the effect never fires"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          revoked (grant/revoke g :breach/cost-exceeded now)
          fired (atom false)
          done (fx/commit! dir t revoked {} now
                           (fn [] (reset! fired true) {:effect/outcome :succeeded}))]
      (is (= :failed (:effect/state done)))
      (is (not @fired) "the effect MUST NOT fire when the re-check refuses")
      (is (re-find #"inactive" (:effect/failure done)))))

  (testing "a grant that expired between propose and commit fails the commit"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          done (fx/commit! dir t g {} much-later
                           (fn [] (reset! fired true) {:effect/outcome :succeeded}))]
      (is (= :failed (:effect/state done)))
      (is (not @fired))))

  (testing "a breached ceiling fails the commit"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          done (fx/commit! dir t g {:usage/count 99} now
                           (fn [] (reset! fired true) {:effect/outcome :succeeded}))]
      (is (= :failed (:effect/state done)))
      (is (not @fired))))

  (testing "no grant at all fails the commit"
    (let [dir (tmp-dir)
          g (merge-grant)
          t (propose-merge! dir g)
          fired (atom false)
          done (fx/commit! dir t nil {} now
                           (fn [] (reset! fired true) {:effect/outcome :succeeded}))]
      (is (= :failed (:effect/state done)))
      (is (not @fired)))))
