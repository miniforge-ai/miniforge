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
            [clojure.string :as str]
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

(deftest ^{:stratum 0} unreadable-gate-history-yields-no-entries-and-says-so
  (let [root (str (Files/createTempDirectory "peg-telemetry-io" (make-array FileAttribute 0)))
        dir (io/file root "run-x")]
    (.mkdirs dir)
    ;; a directory where the file should be: opening it as a file fails
    (.mkdirs (io/file dir sut/gate-history-filename))
    (is (= {:entries [] :unreadable? true :skipped 0} (sut/read-gate-history dir))))
  (let [root (str (Files/createTempDirectory "peg-telemetry-io2" (make-array FileAttribute 0)))
        dir (io/file root "run-y")]
    (.mkdirs dir)
    (is (= {:entries [] :unreadable? false :skipped 0} (sut/read-gate-history dir))
        "a missing file is a run that never wrote one, not incomplete input")))

(deftest ^{:stratum 0} aggregate-prefers-the-mechanism-that-answered
  (let [obs [{:peg "p" :mechanism "no/such/mechanism" :observed? false :answers [] :collapsed? false}
             {:peg "p" :mechanism "miniforge/gate/stale-references" :observed? true :answers [:denied] :collapsed? false}]]
    (is (= "miniforge/gate/stale-references" (get-in (sut/aggregate obs) ["p" :mechanism])))
    (is (= "miniforge/gate/stale-references" (get-in (sut/aggregate (reverse obs)) ["p" :mechanism]))
        "independent of observation order")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} torn-gate-history-rows-flag-the-run-incomplete
  ;; Review catch on #1993 round 4: a torn nonblank gate-history row
  ;; keeps the readable verdicts but must not let the run pass as
  ;; complete — missing verdicts can move the answer distribution.
  (let [root (str (Files/createTempDirectory "peg-telemetry-torn-gh" (make-array FileAttribute 0)))
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}]
    (consult-dir! root "run-a" [drift-peg])
    (spit (io/file root "run-a" sut/gate-history-filename)
          (str (pr-str deny) "
" "{torn row
"))
    (let [{:keys [pegs incomplete-runs incomplete-run-dirs]}
          (sut/peg-telemetry root nodes gate-map)]
      (is (= {:denied 1}
             (:answers (get pegs "did-you-update-every-consumer")))
          "the readable verdict still counts")
      (is (= 1 incomplete-runs))
      (is (str/ends-with? (first incomplete-run-dirs) "run-a")))))

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

(deftest ^{:stratum 1} empty-gate-history-does-not-discard-explicit-answers
  ;; Review catch on #1993: gate-history writes are best-effort. A mapped
  ;; gate with no recorded verdicts must fall back to the recorded
  ;; explicit answer, not report a mechanism source that answered nothing.
  (let [root (str (Files/createTempDirectory "peg-telemetry-nogate" (make-array FileAttribute 0)))]
    (consult-dir! root "run-a" [(assoc drift-peg :answer "yes")])
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= {"yes" 1} (:answers drift)))
      (is (= {:explicit 1} (:answer-sources drift)))
      (is (= 0 (:mechanism-overrode-explicit drift))))))

(deftest ^{:stratum 1} out-of-vocabulary-answers-never-enter-the-stream
  ;; Review catch on #1993: ten "Yes" answers to a peg offering yes/no
  ;; must not zero the entropy. Invalid recordings surface for the
  ;; channel reader instead of counting.
  (let [root (str (Files/createTempDirectory "peg-telemetry-vocab" (make-array FileAttribute 0)))]
    (consult-dir! root "run-a" [(assoc unmapped-peg :answer "X")])
    (consult-dir! root "run-b" [(assoc unmapped-peg :answer "x")])
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (= {"x" 1} (:answers u)) "only the in-vocabulary answer counts")
      (is (= ["X"] (:invalid-answers u)))
      (is (= 1 (:unanswered-runs u))
          "an invalid-only run has no counted answer"))))

(deftest ^{:stratum 1} torn-answer-log-runs-are-lost-not-unanswered
  ;; Copilot catch on #1992 (§7.7.2 lost-vs-unanswered): a torn
  ;; answers.edn parses to nil and every presented peg records :answer
  ;; nil — without the :consultation/answer-log marker those runs
  ;; inflate :unanswered-runs, letting log corruption masquerade as the
  ;; agent declining to answer.
  (let [root (str (Files/createTempDirectory "peg-telemetry-torn" (make-array FileAttribute 0)))
        write! (fn [name pegs answer-log]
                 (let [dir (io/file root name)]
                   (.mkdirs dir)
                   (spit (io/file dir "codex-consultations.edn")
                         (str (pr-str {:consultation/id (random-uuid)
                                       :consultation/phase :implement
                                       :consultation/answer-log answer-log
                                       :consultation/pegs pegs})
                              "\n"))))]
    (write! "run-a" [(assoc unmapped-peg :answer "x")] :recorded)
    (write! "run-b" [unmapped-peg] :unreadable)
    (write! "run-c" [unmapped-peg] :absent)
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (= 3 (:runs u)))
      (is (= {"x" 1} (:answers u)))
      (is (= 1 (:unanswered-runs u))
          "only the intact-log unanswered run counts as unanswered")
      (is (= 1 (:unreadable-log-runs u))
          "the torn-log run reports as lost, not unanswered"))))

(deftest ^{:stratum 1} mixed-source-windows-count-the-mechanism-stream-only
  ;; Review catch on #1993: a peg observed explicitly in some runs and
  ;; via its mechanism in others must not blend the two vocabularies
  ;; into a fake distribution that suppresses the trigger.
  (let [root (str (Files/createTempDirectory "peg-telemetry-mixed" (make-array FileAttribute 0)))
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}]
    (consult-dir! root "run-a" [(assoc drift-peg :answer "yes")])
    (consult-dir! root "run-b" [drift-peg] [deny deny])
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= :mechanism (:counted-source drift)))
      (is (= {:denied 2} (:answers drift))
          "the counted stream is the mechanism runs' alone")
      (is (= {:explicit 1 :mechanism 1} (:answer-sources drift))
          "both sources stay visible even though one is counted"))))

(deftest ^{:stratum 1} incomplete-run-dirs-are-named-not-quiet
  ;; Review catches on #1993: incomplete input must be named, never
  ;; presented as a quiet run — an IO failure on any record file, a
  ;; torn row the line reader skipped, or an unreadable existing
  ;; gate-history all count.
  (let [root (str (Files/createTempDirectory "peg-telemetry-unread" (make-array FileAttribute 0)))]
    ;; a DIRECTORY at the consultations path forces a read IOException
    (.mkdirs (io/file root "run-a" "codex-consultations.edn"))
    ;; torn consultation row: readable file, one skipped line
    (.mkdirs (io/file root "run-b"))
    (spit (io/file root "run-b" "codex-consultations.edn") "{torn
")
    ;; unreadable existing gate history beside readable records
    (consult-dir! root "run-c" [unmapped-peg])
    (.mkdirs (io/file root "run-c" sut/gate-history-filename))
    (let [{:keys [incomplete-runs incomplete-run-dirs]}
          (sut/peg-telemetry root nodes gate-map)]
      (is (= 3 incomplete-runs))
      (is (= #{"run-a" "run-b" "run-c"}
             (into #{} (map #(last (str/split % #"/"))) incomplete-run-dirs))))))

(deftest ^{:stratum 1} overridden-explicit-values-stay-inspectable
  ;; Review catch on #1993 round 3: the override COUNT alone cannot say
  ;; whether the agent disagreed with the gate — keep the values.
  (let [root (str (Files/createTempDirectory "peg-telemetry-vals" (make-array FileAttribute 0)))
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}]
    (consult-dir! root "run-a" [(assoc drift-peg :answer "yes")] [deny])
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= ["yes"] (:explicit-answers drift)))
      (is (= {:denied 1} (:answers drift))))))

(deftest ^{:stratum 1} reported-mechanism-labels-the-counted-stream
  ;; Review catch on #1993 round 3: an explicit-only run's unrelated
  ;; mechanism must not label another gate's verdicts.
  (let [root (str (Files/createTempDirectory "peg-telemetry-label" (make-array FileAttribute 0)))
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}
        ;; same peg id, two landing snapshots: one run's landings carry
        ;; only the unmapped mechanism, the other's the mapped one
        unmapped-snapshot {:id "did-you-update-every-consumer" :answer "yes"
                           :landings {"yes" ["unmapped-problem"] "no" ["other-problem"]}}]
    (consult-dir! root "run-a" [unmapped-snapshot])
    (consult-dir! root "run-b" [drift-peg] [deny])
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= {:denied 1} (:answers drift)))
      (is (= "miniforge/gate/stale-references" (:mechanism drift))
          "the mechanism names the gate whose verdicts were counted"))))

(deftest ^{:stratum 1} miss-ledger-answers-survive-a-lost-consultation-file
  ;; Review catch on #1993 round 3: build-entry copies answered peg rows
  ;; into :miss/pegs; when the consultation file is gone, that surviving
  ;; answer must still observe the peg (vocabulary validation intact).
  (let [root (str (Files/createTempDirectory "peg-telemetry-missans" (make-array FileAttribute 0)))
        dir (io/file root "run-a")]
    (.mkdirs dir)
    (spit (io/file dir "codex-gap-ledger.edn")
          (str (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [(assoc unmapped-peg :answer "x")
                                    (assoc drift-peg :answer "not-in-vocabulary")]})
               "
"))
    (let [{:keys [pegs]} (sut/peg-telemetry root nodes gate-map)]
      (is (= {"x" 1} (:answers (get pegs "unmapped"))))
      (is (= ["not-in-vocabulary"] (:invalid-answers (get pegs "did-you-update-every-consumer")))
          "the fallback answer still passes vocabulary validation"))))

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

(deftest ^{:stratum 1} answered-miss-rows-never-displace-consultation-snapshots
  ;; Review catch on #1993 round 5: an answered miss row must not
  ;; replace a newer consultation row's landing snapshot — stale
  ;; landings pick stale mechanisms. It may only donate an answer the
  ;; consultation rows lack.
  (let [root (str (Files/createTempDirectory "peg-telemetry-displace" (make-array FileAttribute 0)))
        ;; consultation snapshot: landings WITHOUT a mapped mechanism,
        ;; answered explicitly
        consult-snapshot {:id "did-you-update-every-consumer" :answer "yes"
                          :landings {"yes" ["unmapped-problem"] "no" ["other-problem"]}}
        ;; stale miss snapshot: answered, landings WITH the mapped mechanism
        miss-snapshot (assoc drift-peg :answer "no")
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}]
    (consult-dir! root "run-a" [consult-snapshot] [deny])
    (spit (io/file root "run-a" "codex-gap-ledger.edn")
          (str (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [miss-snapshot]})
               "\n"))
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= {"yes" 1} (:answers drift))
          "the consultation snapshot's unmapped landings mean the explicit
           answer counts — the stale miss row's mechanism does not")
      (is (= {:explicit 1} (:answer-sources drift))))
    (testing "an unanswered consultation row accepts a miss row's answer"
      (let [root2 (str (Files/createTempDirectory "peg-telemetry-donate" (make-array FileAttribute 0)))]
        (consult-dir! root2 "run-a" [(dissoc consult-snapshot :answer)])
        (spit (io/file root2 "run-a" "codex-gap-ledger.edn")
              (str (pr-str {:miss/id (random-uuid) :miss/phase :implement
                            :miss/pegs [(assoc consult-snapshot :answer "no")]})
                   "\n"))
        (let [drift (get-in (sut/peg-telemetry root2 nodes gate-map)
                            [:pegs "did-you-update-every-consumer"])]
          (is (= {"no" 1} (:answers drift))
              "the donated answer counts against the consultation landings"))))))

(deftest ^{:stratum 1} answered-miss-only-duplicates-carry-their-own-metadata
  ;; Review catch on #1993 round 6: with no consultation file, an
  ;; answered later miss row must supply its own landing snapshot —
  ;; only consultation rows are protected metadata.
  (let [root (str (Files/createTempDirectory "peg-telemetry-missdup" (make-array FileAttribute 0)))
        dir (io/file root "run-a")
        stale {:id "did-you-update-every-consumer"
               :landings {"a" ["unmapped-problem"] "b" ["other-problem"]}}
        answered (assoc drift-peg :answer "no")]
    (.mkdirs dir)
    (spit (io/file dir "codex-gap-ledger.edn")
          (str (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [stale]})
               "\n"
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [answered]})
               "\n"))
    (let [drift (get-in (sut/peg-telemetry root nodes gate-map)
                        [:pegs "did-you-update-every-consumer"])]
      (is (= {"no" 1} (:answers drift))
          "the answer validates against the ANSWERED row's vocabulary")
      (is (= [] (:invalid-answers drift))))))

(deftest ^{:stratum 1} answers-validate-against-their-own-consultations-vocabulary
  ;; Review catch on #1993 round 6: vocabularies can change between
  ;; consultations within one run; each answer validates against the
  ;; landing map it was given against, not the merge-selected snapshot.
  (let [root (str (Files/createTempDirectory "peg-telemetry-vocabchange" (make-array FileAttribute 0)))
        early {:id "shifting" :answer "x"
               :landings {"x" ["unmapped-problem"] "y" ["other-problem"]}}
        late {:id "shifting" :answer "yes"
              :landings {"yes" ["other-problem"] "no" ["unmapped-problem"]}}
        dir (io/file root "run-a")]
    (.mkdirs dir)
    (spit (io/file dir "codex-consultations.edn")
          (str (pr-str {:consultation/id (random-uuid)
                        :consultation/phase :implement
                        :consultation/pegs [early]})
               "\n"
               (pr-str {:consultation/id (random-uuid)
                        :consultation/phase :review
                        :consultation/pegs [late]})
               "\n"))
    (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
      (is (= {"x" 1 "yes" 1} (:answers s))
          "both answers were valid against their own vocabulary")
      (is (= [] (:invalid-answers s))
          "neither is rejected against the other's landing map"))))

(deftest ^{:stratum 1} donated-answers-validate-against-the-donor-vocabulary
  ;; Review catch on #1993 round 7: a miss answer recorded against x/y
  ;; must not be judged by a surviving unanswered consultation's yes/no
  ;; vocabulary — the donor's landing map validates its own answer while
  ;; the consultation snapshot still owns mechanism selection.
  (let [root (str (Files/createTempDirectory "peg-telemetry-donorvocab" (make-array FileAttribute 0)))
        survivor {:id "shifting"
                  :landings {"yes" ["other-problem"] "no" ["unmapped-problem"]}}
        donor {:id "shifting" :answer "x"
               :landings {"x" ["unmapped-problem"] "y" ["other-problem"]}}
        dir (io/file root "run-a")]
    (.mkdirs dir)
    (spit (io/file dir "codex-consultations.edn")
          (str (pr-str {:consultation/id (random-uuid)
                        :consultation/phase :implement
                        :consultation/pegs [survivor]})
               "\n"))
    (spit (io/file dir "codex-gap-ledger.edn")
          (str (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [donor]})
               "\n"))
    (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
      (is (= {"x" 1} (:answers s))
          "the donated answer is valid against ITS OWN vocabulary")
      (is (= [] (:invalid-answers s))))))

(deftest ^{:stratum 1} lost-consultation-file-keeps-per-consultation-answer-counts
  ;; Review catch on #1993 round 8: with the consultation file missing,
  ;; answers from SEPARATE phase consultations each count (the recovered
  ;; stream matches what the file would have recorded), while the
  ;; multiple entries one leave writes for its several failure signals
  ;; collapse to one.
  (let [root (str (Files/createTempDirectory "peg-telemetry-missfile" (make-array FileAttribute 0)))
        dir (io/file root "run-a")
        answered (assoc unmapped-peg :answer "x")]
    (.mkdirs dir)
    (spit (io/file dir "codex-gap-ledger.edn")
          (str ;; implement leave, two failure signals -> identical copies
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [answered]}) "\n"
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/pegs [answered]}) "\n"
               ;; review leave: a separate consultation, same answer
               (pr-str {:miss/id (random-uuid) :miss/phase :review
                        :miss/pegs [answered]}) "\n"))
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (= {"x" 2} (:answers u))
          "two consultations count twice; duplicate signal copies do not")
      (is (= 2 (:observations u))))))

(deftest ^{:stratum 1} same-phase-retries-count-separately-by-identity
  ;; Review catch on #1993 round 9: two attempts of one phase are two
  ;; consultations; their identical answers both count when the
  ;; consultation file is lost, while one attempt's several signal
  ;; copies (same identity) collapse.
  (let [root (str (Files/createTempDirectory "peg-telemetry-retry" (make-array FileAttribute 0)))
        dir (io/file root "run-a")
        answered (assoc unmapped-peg :answer "x")
        cid1 (random-uuid)
        cid2 (random-uuid)]
    (.mkdirs dir)
    (spit (io/file dir "codex-gap-ledger.edn")
          (str ;; attempt 1, two signals (same identity)
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/consultation {:consultation-id cid1}
                        :miss/pegs [answered]}) "\n"
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/consultation {:consultation-id cid1}
                        :miss/pegs [answered]}) "\n"
               ;; attempt 2: same phase, same rows, NEW identity
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/consultation {:consultation-id cid2}
                        :miss/pegs [answered]}) "\n"))
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (= {"x" 2} (:answers u))
          "two identities count twice; one identity's copies collapse"))))

(deftest ^{:stratum 1} partially-lost-consultation-file-recovers-the-missing-identity
  ;; Review catch on #1993 round 9: when one consultation's rows survive
  ;; in the file and another's were lost, the lost one recovers from its
  ;; miss copies — union, not all-or-nothing per peg.
  (let [root (str (Files/createTempDirectory "peg-telemetry-partial" (make-array FileAttribute 0)))
        dir (io/file root "run-a")
        survived-id (random-uuid)
        lost-id (random-uuid)]
    (.mkdirs dir)
    (spit (io/file dir "codex-consultations.edn")
          (str (pr-str {:consultation/id survived-id
                        :consultation/phase :implement
                        :consultation/pegs [(assoc unmapped-peg :answer "x")]})
               "\n"))
    (spit (io/file dir "codex-gap-ledger.edn")
          (str ;; the survived consultation's miss copy: contributes nothing
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/consultation {:consultation-id survived-id}
                        :miss/pegs [(assoc unmapped-peg :answer "x")]}) "\n"
               ;; the LOST consultation's miss copy: recovers
               (pr-str {:miss/id (random-uuid) :miss/phase :review
                        :miss/consultation {:consultation-id lost-id}
                        :miss/pegs [(assoc unmapped-peg :answer "y")]}) "\n"))
    (let [u (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "unmapped"])]
      (is (= {"x" 1 "y" 1} (:answers u))
          "the file's answer and the lost identity's recovery both count, once each"))))

(deftest ^{:stratum 1} metadata-unions-over-every-snapshot-the-run-presented
  ;; Review catch on #1993 round 10: a peg presented with two landing
  ;; snapshots in one run (one mapped and collapsed, one unmapped and
  ;; answered) must keep the gate verdicts AND the collapsed-branch
  ;; trigger whichever order the snapshots arrived in.
  (let [deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}
        mapped-collapsed {:id "shifting"
                          :landings {"a" ["contract-drift-is-silent"]
                                     "b" ["contract-drift-is-silent"]}}
        unmapped-open (assoc {:id "shifting"
                              :landings {"yes" ["other-problem"]
                                         "no" ["unmapped-problem"]}}
                             :answer "yes")]
    (doseq [rows [[mapped-collapsed unmapped-open]
                  [unmapped-open mapped-collapsed]]]
      (let [root (str (Files/createTempDirectory "peg-telemetry-union" (make-array FileAttribute 0)))]
        (consult-dir! root "run-a" rows [deny])
        (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
          (is (= "miniforge/gate/stale-references" (:mechanism s))
              "the mapped snapshot's mechanism survives either order")
          (is (= {:denied 1} (:answers s))
              "the gate verdict is the counted stream")
          (is (= ["yes"] (:explicit-answers s))
              "the overridden explicit answer stays inspectable")
          (is (= 1 (:collapsed-runs s))
              "the collapsed snapshot's trigger survives either order"))))))

(deftest ^{:stratum 1} one-answer-counts-once-across-same-id-snapshots
  ;; Review catch on #1993 round 11: the writer stamps the recorded
  ;; answer onto every same-id snapshot it keeps; counting per row would
  ;; inflate :observations. One consultation counts once; separate
  ;; consultations still count separately; validity holds when ANY of
  ;; the id's snapshots accepts the answer.
  (let [root (str (Files/createTempDirectory "peg-telemetry-oneanswer" (make-array FileAttribute 0)))
        snap-a {:id "shifting" :answer "x"
                :landings {"x" ["unmapped-problem"] "y" ["other-problem"]}}
        snap-b {:id "shifting" :answer "x"
                :landings {"x" ["other-problem"] "z" ["unmapped-problem"]}}
        dir (io/file root "run-a")]
    (.mkdirs dir)
    (spit (io/file dir "codex-consultations.edn")
          (str ;; one consultation, two snapshots carrying the one answer
               (pr-str {:consultation/id (random-uuid)
                        :consultation/phase :implement
                        :consultation/pegs [snap-a snap-b]}) "\n"
               ;; a separate consultation: counts separately
               (pr-str {:consultation/id (random-uuid)
                        :consultation/phase :review
                        :consultation/pegs [snap-a]}) "\n"))
    (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
      (is (= {"x" 2} (:answers s))
          "two consultations, two observations — not three")
      (is (= 2 (:observations s)))))
  (testing "the answer is valid when only the OTHER snapshot accepts it"
    (let [root (str (Files/createTempDirectory "peg-telemetry-anyrow" (make-array FileAttribute 0)))
          rejecting {:id "shifting" :answer "x"
                     :landings {"yes" ["other-problem"] "no" ["unmapped-problem"]}}
          accepting {:id "shifting" :answer "x"
                     :landings {"x" ["unmapped-problem"]}}
          dir (io/file root "run-a")]
      (.mkdirs dir)
      (spit (io/file dir "codex-consultations.edn")
            (str (pr-str {:consultation/id (random-uuid)
                          :consultation/phase :implement
                          :consultation/pegs [rejecting accepting]}) "\n"))
      (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
        (is (= {"x" 1} (:answers s)))
        (is (= [] (:invalid-answers s)))))))

(deftest ^{:stratum 1} lost-identity-snapshots-union-with-the-survivors
  ;; Review catch on #1993 round 12: a lost consultation's metadata
  ;; counts with its answers — its mapped, collapsed snapshot joins the
  ;; union even though the file's surviving consultation presented the
  ;; same peg id — while a stale miss copy of a SURVIVING identity
  ;; still adds nothing.
  (let [root (str (Files/createTempDirectory "peg-telemetry-lostmeta" (make-array FileAttribute 0)))
        dir (io/file root "run-a")
        survived-id (random-uuid)
        lost-id (random-uuid)
        survivor {:id "shifting"
                  :landings {"yes" ["other-problem"] "no" ["unmapped-problem"]}}
        lost-collapsed {:id "shifting" :answer "a"
                        :landings {"a" ["contract-drift-is-silent"]
                                   "b" ["contract-drift-is-silent"]}}
        stale-copy {:id "shifting"
                    :landings {"stale" ["unmapped-problem"]}}
        deny {:phase :implement :decision :deny
              :phase/gate-failures [{:gate :stale-references}]}]
    (.mkdirs dir)
    (spit (io/file dir "codex-consultations.edn")
          (str (pr-str {:consultation/id survived-id
                        :consultation/phase :implement
                        :consultation/pegs [survivor]}) "\n"))
    (spit (io/file dir sut/gate-history-filename)
          (str (pr-str deny) "\n"))
    (spit (io/file dir "codex-gap-ledger.edn")
          (str ;; stale copy of the SURVIVING identity: adds nothing
               (pr-str {:miss/id (random-uuid) :miss/phase :implement
                        :miss/consultation {:consultation-id survived-id}
                        :miss/pegs [stale-copy]}) "\n"
               ;; the LOST identity: metadata and answer both recover
               (pr-str {:miss/id (random-uuid) :miss/phase :review
                        :miss/consultation {:consultation-id lost-id}
                        :miss/pegs [lost-collapsed]}) "\n"))
    (let [s (get-in (sut/peg-telemetry root nodes gate-map) [:pegs "shifting"])]
      (is (= "miniforge/gate/stale-references" (:mechanism s))
          "the lost consultation's mapped landing picks the mechanism")
      (is (= {:denied 1} (:answers s))
          "its gate verdict is the counted stream")
      (is (= 1 (:collapsed-runs s))
          "its collapse signature survives")
      (is (= ["a"] (:explicit-answers s))
          "its recovered explicit answer stays inspectable"))))
