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
  ;; The production per-peg record: :answer nil, landings under :landings.
  {:id "did-you-update-every-consumer" :answer nil
   :landings {"yes" ["other-problem"] "no" ["contract-drift-is-silent"]}})

(def ^{:stratum 0} collapsed-peg
  {:id "collapsed" :answer nil :landings {"a" ["other-problem"] "b" ["other-problem"]}})

(def ^{:stratum 0} unmapped-peg
  {:id "unmapped" :answer nil :landings {"x" ["unmapped-problem"] "y" ["other-problem"]}})

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
  (let [peg {:id "p" :answer nil :landings {"a" ["m-b"] "b" ["m-a"]}}
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

(defn- ^{:stratum 0} consult-dir!
  "A checkpoint run dir with one consultation entry presenting `pegs`
   (SPEC §7.7.2.1 shape) and optionally a gate history."
  ([root name pegs] (consult-dir! root name pegs nil))
  ([root name pegs entries]
   (let [dir (io/file root name)]
     (.mkdirs dir)
     (spit (io/file dir "codex-consultations.edn")
           (str (pr-str {:consultation/id (random-uuid)
                         :consultation/phase :implement
                         :consultation/pegs pegs})
                "\n"))
     (when entries
       (spit (io/file dir sut/gate-history-filename)
             (apply str (map #(str (pr-str %) "\n") entries))))
     dir)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} entropy-and-collapse-primitives
  (is (= 0.0 (sut/entropy-bits {:denied 5})))
  (is (= 1.0 (sut/entropy-bits {:denied 5 :allowed 5})))
  (is (= 0.0 (sut/entropy-bits {})))
  (is (true? (sut/branches-collapsed? collapsed-peg)))
  (is (false? (sut/branches-collapsed? drift-peg)))
  (is (false? (sut/branches-collapsed? {:id "one" :landings {"only" ["x"]}})))
  (is (true? (sut/branches-collapsed? {:id "raw" :answers {"a" ["x"] "b" ["x"]}}))
      "the raw codex basis key is read too"))

(deftest ^{:stratum 1} explicit-answers-observe-mechanismless-pegs
  ;; SPEC §7.7.2: a peg with no mapped mechanism was unobservable before
  ;; the answer channel; an explicit answer_peg recording now observes it.
  ;; The consultation record alone suffices — clean runs write no miss.
  (let [root (str (Files/createTempDirectory "peg-telemetry-exp" (make-array FileAttribute 0)))]
    (consult-dir! root "run-a" [(assoc unmapped-peg :answer "x")])
    (consult-dir! root "run-b" [(assoc unmapped-peg :answer "x")])
    (consult-dir! root "run-c" [unmapped-peg])
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (true? (:observed? u)))
      (is (= {"x" 2} (:answers u)))
      (is (= {:explicit 2} (:answer-sources u)))
      (is (= 1 (:unanswered-runs u))
          "presented-but-unanswered stays a distinguishable observation")
      (is (nil? (:trigger u)) "two observations cannot trigger"))))

(deftest ^{:stratum 1} mechanism-outranks-explicit-answers
  ;; SPEC §7.7.2.2: where a peg has both a gate verdict and an explicit
  ;; answer in the same run, the mechanism's verdict is counted; the
  ;; explicit answers stay readable for the disagreement reader without
  ;; entering the entropy stream (and without mixing vocabularies).
  (let [root (str (Files/createTempDirectory "peg-telemetry-mech" (make-array FileAttribute 0)))]
    (consult-dir! root "run-a" [(assoc drift-peg :answer "yes")]
                  [{:phase :implement :decision :deny
                    :phase/gate-failures [{:gate :stale-references}]}])
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= {:denied 1} (:answers drift))
          "the counted stream is the gate's, not the self-report")
      (is (= {:mechanism 1} (:answer-sources drift)))
      (is (= 1 (:mechanism-overrode-explicit drift))))))

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
        peg {:id "two-mechanisms" :answer nil :landings {"a" ["unmapped-problem"] "b" ["contract-drift-is-silent"]}}]
    (run-dir! root "run-a" [peg]
              [{:phase :implement :decision :deny :phase/gate-failures [{:gate :stale-references}]}])
    (let [rec (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "two-mechanisms"])]
      (is (= "miniforge/gate/stale-references" (:mechanism rec))
          "the unmapped mechanism sorts first but did not answer")
      (is (= {:denied 1} (:answers rec))))))
