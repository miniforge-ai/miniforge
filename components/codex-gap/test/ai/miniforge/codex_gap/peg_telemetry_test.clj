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
(ns ai.miniforge.codex-gap.peg-telemetry-test
  "§7.7 per-peg telemetry from a ledger plus gate-history: mechanism
   verdicts are the recorded answers; identical landing sets are a
   collapsed branch; nothing is invented for unmapped pegs."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.codex-gap.peg-telemetry :as sut])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} nodes
  {"contract-drift-is-silent" {:id "contract-drift-is-silent" :type "problem"
                               :mechanism "miniforge/gate/stale-references"}
   "other-problem" {:id "other-problem" :type "problem"}
   "unmapped-problem" {:id "unmapped-problem" :type "problem" :mechanism "no/such/mechanism"}})

(def ^{:stratum 0} gate-map {"miniforge/gate/stale-references" :stale-references})

(def ^{:stratum 0} drift-peg
  {:id "did-you-update-every-consumer"
   :answers {"yes" ["other-problem"] "no" ["contract-drift-is-silent"]}})

(def ^{:stratum 0} collapsed-peg
  {:id "collapsed" :answers {"a" ["other-problem"] "b" ["other-problem"]}})

(def ^{:stratum 0} unmapped-peg
  {:id "unmapped" :answers {"x" ["unmapped-problem"] "y" ["other-problem"]}})

(defn- ^{:stratum 0} run-dir!
  "A checkpoint run dir with one ledger entry presenting `pegs` and the
   given gate-history `entries`."
  [root name pegs entries]
  (let [dir (io/file root name)]
    (.mkdirs dir)
    (spit (io/file dir "codex-gap-ledger.edn")
          (pr-str {:miss/id (random-uuid) :miss/phase :implement :miss/pegs pegs}))
    (spit (io/file dir sut/gate-history-filename)
          (apply str (map #(str (pr-str %) "\n") entries)))
    dir))

(deftest ^{:stratum 0} gate-answers-read-implement-iterations-only
  (let [history [{:phase :plan :decision :allow}
                 {:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}
                 {:phase :implement :decision :allow}
                 {:phase :verify :decision :deny :phase/gate-failures [{:gate :policy-verify}]}
                 {:phase :implement :decision :deny :phase/gate-failures [{:gate :lint}]}]]
    (is (= [:denied :allowed :allowed] (sut/gate-answers history :stale-references))
        "a deny on another gate is an allow for this one")))

(deftest ^{:stratum 0} mechanisms-are-sorted-for-stable-choice
  (let [peg {:id "p" :answers {"a" ["m-b"] "b" ["m-a"]}}
        nodes {"m-a" {:id "m-a" :mechanism "z/mechanism"} "m-b" {:id "m-b" :mechanism "a/mechanism"}}]
    (is (= ["a/mechanism" "z/mechanism"] (sut/peg-mechanisms peg nodes)))))

(deftest ^{:stratum 0} unreadable-gate-history-yields-no-entries
  (let [root (str (Files/createTempDirectory "peg-telemetry-io" (make-array FileAttribute 0)))
        dir (io/file root "run-x")]
    (.mkdirs dir)
    ;; a directory where the file should be: opening it as a file fails
    (.mkdirs (io/file dir sut/gate-history-filename))
    (is (= [] (sut/read-gate-history dir)))))

(deftest ^{:stratum 0} aggregate-prefers-the-mechanism-that-answered
  (let [obs [{:peg "p" :mechanism "no/such/mechanism" :observed? false :answers [] :collapsed? false}
             {:peg "p" :mechanism "miniforge/gate/stale-references" :observed? true :answers [:denied] :collapsed? false}]]
    (is (= "miniforge/gate/stale-references" (get-in (sut/aggregate obs) ["p" :mechanism])))
    (is (= "miniforge/gate/stale-references" (get-in (sut/aggregate (reverse obs)) ["p" :mechanism]))
        "independent of observation order")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} entropy-and-collapse-primitives
  (is (= 0.0 (sut/entropy-bits {:denied 5})))
  (is (= 1.0 (sut/entropy-bits {:denied 5 :allowed 5})))
  (is (= 0.0 (sut/entropy-bits {})))
  (is (true? (sut/branches-collapsed? collapsed-peg)))
  (is (false? (sut/branches-collapsed? drift-peg)))
  (is (false? (sut/branches-collapsed? {:id "one" :answers {"only" ["x"]}}))))

(deftest ^{:stratum 1} telemetry-over-runs
  (let [root (str (Files/createTempDirectory "peg-telemetry" (make-array FileAttribute 0)))]
    (run-dir! root "run-a" [drift-peg collapsed-peg unmapped-peg]
              [{:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}
               {:phase :implement :decision :allow}])
    (run-dir! root "run-b" [drift-peg]
              [{:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}])
    (.mkdirs (io/file root "run-c-no-ledger"))
    (let [{:keys [pegs runs-with-pegs runs-scanned]} (sut/peg-telemetry root nodes gate-map)
          drift (get pegs "did-you-update-every-consumer")]
      (testing "runs are counted honestly"
        (is (= 3 runs-scanned))
        (is (= 2 runs-with-pegs)))
      (testing "the mechanism's verdicts are the recorded answers"
        (is (= "miniforge/gate/stale-references" (:mechanism drift)))
        (is (= {:denied 2 :allowed 1} (:answers drift)))
        (is (= 3 (:observations drift)))
        (is (true? (:observed? drift)))
        (is (nil? (:trigger drift)) "three observations cannot trigger"))
      (testing "a collapsed branch triggers regardless of counts"
        (is (= :branches-collapsed (:trigger (get pegs "collapsed")))))
      (testing "an unmapped mechanism yields no answers and no trigger"
        (let [u (get pegs "unmapped")]
          (is (false? (:observed? u)))
          (is (= {} (:answers u)))
          (is (nil? (:trigger u))))))))

(deftest ^{:stratum 1} entropy-trigger-needs-enough-observations
  (let [root (str (Files/createTempDirectory "peg-telemetry-ent" (make-array FileAttribute 0)))
        deny {:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}]
    (run-dir! root "run-a" [drift-peg] (repeat sut/min-observations deny))
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "did-you-update-every-consumer"])]
      (is (= {:denied sut/min-observations} (:answers drift)))
      (is (= 0.0 (:entropy-bits drift)))
      (is (= :entropy (:trigger drift))))))

(deftest ^{:stratum 1} reported-mechanism-is-the-one-that-answered
  (let [root (str (Files/createTempDirectory "peg-telemetry-mech" (make-array FileAttribute 0)))
        peg {:id "two-mechanisms" :answers {"a" ["unmapped-problem"] "b" ["contract-drift-is-silent"]}}]
    (run-dir! root "run-a" [peg]
              [{:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}])
    (let [rec (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "two-mechanisms"])]
      (is (= "miniforge/gate/stale-references" (:mechanism rec))
          "the unmapped mechanism sorts first but did not answer")
      (is (= {:denied 1} (:answers rec))))))
