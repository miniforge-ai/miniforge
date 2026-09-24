;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.interface-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as content-hash]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} candidate
  {:workflow-run/id #uuid "b4bd9e4f-57ac-40fb-a724-26f4619a47fe"
   :effect/id #uuid "0ccdfbe7-cc0b-4f0f-a8a5-23fb6aa7e061"
   :pr/repo "example/opsv"
   :pr/base "main"
   :pr/branch "opsv/scaling"
   :pr/head-sha "0123456789012345678901234567890123456789"
   :pr/title "Tune catalog scaling"
   :opsv/policy-diff "HPA target: 70 -> 65"
   :opsv/evidence-bundle-id #uuid "00000000-0000-0000-0000-000000000701"
   :opsv/rollback-instructions "Restore the previous policy revision."
   :opsv/verification-result
   {:passed? true :confidence :high :caveats []
    :criteria-evaluation [{:criterion/id "latency"
                           :criterion/passed? true
                           :criterion/observed 175
                           :criterion/expected 200
                           :criterion/reason-code :within-threshold}]}})

(defn- ^{:stratum 0} provider-content
  [proposal]
  (dissoc proposal :workflow-run/id :effect/id :pr/payload-hash))

(defn- ^{:stratum 0} reverse-map
  [value]
  (into (array-map) (reverse (seq value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-observation
  [field value]
  (assoc-in candidate [:opsv/verification-result :criteria-evaluation 0 field] value))

(deftest ^{:stratum 1} proposal-carries-evidence-and-exact-payload-test
  (let [proposal (actuation/prepare-pr candidate)
        body (:pr/body proposal)]
    (is (false? (:pr/draft? proposal)))
    (is (= (:workflow-run/id candidate) (:workflow-run/id proposal)))
    (is (= (:effect/id candidate) (:effect/id proposal)))
    (is (= (:pr/title candidate) (:pr/title proposal)))
    (doseq [field [:opsv/policy-diff :opsv/evidence-bundle-id
                  :opsv/rollback-instructions]]
      (is (str/includes? body (str (get candidate field)))))
    (is (str/includes? body "latency"))
    (is (= (content-hash/content-hash (provider-content proposal))
           (:pr/payload-hash proposal)))))

(deftest ^{:stratum 1} failed-or-incomplete-verification-forces-draft-test
  (doseq [[path value] [[[:passed?] false]
                        [[:criteria-evaluation] []]
                        [[:criteria-evaluation 0 :criterion/passed?] false]]]
    (let [input (assoc-in candidate (into [:opsv/verification-result] path) value)
          proposal (actuation/prepare-pr input)]
      (is (true? (:pr/draft? proposal)))
      (is (str/includes? (:pr/body proposal) "ineligible for merge")))))

(deftest ^{:stratum 1} proposal-boundary-refuses-missing-data-test
  (doseq [field (keys candidate)]
    (is (anomaly/anomaly? (actuation/prepare-pr (dissoc candidate field)))))
  (doseq [field [:opsv/policy-diff :opsv/rollback-instructions :pr/title]
          value [nil "" " "]]
    (is (anomaly/anomaly? (actuation/prepare-pr (assoc candidate field value)))))
  (is (anomaly/anomaly? (actuation/prepare-pr (assoc candidate :pr/draft? false)))))

(deftest ^{:stratum 1} digest-is-stable-and-content-bound-test
  (let [proposal (actuation/prepare-pr candidate)
        reordered (update candidate :opsv/verification-result reverse-map)]
    (is (= proposal (actuation/prepare-pr reordered)))
    (doseq [field [:pr/title :opsv/policy-diff :opsv/rollback-instructions
                  :pr/repo :pr/base :pr/branch]]
      (let [changed (actuation/prepare-pr (update candidate field str "-changed"))]
        (is (not= (:pr/payload-hash proposal) (:pr/payload-hash changed)))))))

(deftest ^{:stratum 1} proposal-requires-a-full-git-object-id-test
  (doseq [sha ["0123456" "ABCDEF0123456789012345678901234567890123"
              "z123456789012345678901234567890123456789" "" nil]]
    (is (anomaly/anomaly? (actuation/prepare-pr (assoc candidate :pr/head-sha sha)))))
  (let [original (actuation/prepare-pr candidate)]
    (doseq [sha [(apply str (repeat 40 "a")) (apply str (repeat 64 "b"))]]
      (let [proposal (actuation/prepare-pr (assoc candidate :pr/head-sha sha))]
        (is (not (anomaly/anomaly? proposal)))
        (is (= sha (:pr/head-sha proposal)))
        (is (not= (:pr/payload-hash original) (:pr/payload-hash proposal)))))))

(deftest ^{:stratum 1} payload-content-is-not-template-code-test
  (testing "operator content containing placeholder syntax is preserved"
    (let [literal "Preserve {bundle-id} and {rollback-instructions} literally."
          input (assoc candidate :opsv/policy-diff literal)
          proposal (actuation/prepare-pr input)]
      (is (str/includes? (:pr/body proposal) literal)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} verification-must-be-portable-data-test
  (doseq [field [:criterion/observed :criterion/expected]
          value [(Object.) identity {:nested [(Object.)]} {(Object.) 1}
                 (tagged-literal 'custom "value")]]
    (is (anomaly/anomaly? (actuation/prepare-pr (with-observation field value)))))
  (let [observed {:latency [175 175.5 175M 1/2 nil true :ok]
                  "sample" (:opsv/evidence-bundle-id candidate)}
        proposal (actuation/prepare-pr (with-observation :criterion/observed observed))
        reordered (actuation/prepare-pr
                   (with-observation :criterion/observed (reverse-map observed)))]
    (is (not (anomaly/anomaly? proposal)))
    (is (= proposal reordered))))

(comment
  (actuation/prepare-pr candidate))
