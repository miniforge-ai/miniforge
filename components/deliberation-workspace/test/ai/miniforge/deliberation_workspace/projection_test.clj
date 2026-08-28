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
(ns ai.miniforge.deliberation-workspace.projection-test
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.projection :as projection]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} obj [id type role & {:keys [attrs links touched-at]}]
  (assoc (object/new-object {:id id :type type :statement (str "statement " id)
                             :role role :activation "act-1" :version 1
                             :attrs attrs :links links})
         :object/touched-at (or touched-at 1)))

(defn- ^{:stratum 0} workspace [& objects]
  {:workspace/version 12
   :workspace/objects (into {} (map (juxt :object/id identity)) objects)})

(defn- ^{:stratum 0} ids [objects]
  (mapv :object/id objects))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private goal (obj "goal-1" :goal :interpreter))

(def ^{:stratum 1} ^:private hard (obj "constraint-1" :constraint :interpreter {:attrs {:kind :hard}}))

(def ^{:stratum 1} ^:private own-claim (obj "claim-own" :claim :skeptic))

(def ^{:stratum 1} ^:private foreign-claim (obj "claim-foreign" :claim :proposer))

(def ^{:stratum 1} ^:private interpreter-claim (obj "claim-spec" :claim :interpreter))

(def ^{:stratum 1} ^:private user-claim (obj "claim-user" :claim :user))

(deftest ^{:stratum 1} resolved-conflicts-are-not-rendered
  (let [conflict (assoc (obj "conflict-1" :conflict :skeptic) :object/status :resolved)]
    (is (empty? (:projection/conflicts
                 (projection/project (workspace conflict) :skeptic {}))))))

(deftest ^{:stratum 1} the-delta-carries-what-moved-since-the-last-activation
  (let [old (obj "claim-old" :claim :skeptic)
        recent (obj "claim-recent" :claim :skeptic {:touched-at 11})
        p (projection/project (workspace recent old) :skeptic {:since 10})]
    (is (= ["claim-recent"] (ids (:projection/delta p))))))

(deftest ^{:stratum 1} the-delta-respects-the-ablation
  (let [foreign-recent (obj "claim-foreign" :claim :proposer {:touched-at 11})
        p (projection/project (workspace foreign-recent) :skeptic
                              {:visibility :none :since 10})]
    (is (empty? (:projection/delta p)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} full-visibility-shows-every-object
  (let [p (projection/project (workspace goal own-claim foreign-claim) :skeptic {})]
    (is (= ["claim-foreign" "claim-own" "goal-1"] (ids (:projection/objects p))))))

(deftest ^{:stratum 2} ablation-hides-only-other-roles-contributions
  (let [ws (workspace goal hard own-claim foreign-claim interpreter-claim user-claim)
        p (projection/project ws :skeptic {:visibility :none})
        visible (set (ids (:projection/objects p)))]
    (testing "the role keeps its own objects"
      (is (contains? visible "claim-own")))
    (testing "goals and hard constraints remain shared frame"
      (is (contains? visible "goal-1"))
      (is (contains? visible "constraint-1")))
    (testing "interpreter objects are specification-derived and stay visible"
      (is (contains? visible "claim-spec")))
    (testing "user-injected objects stay visible"
      (is (contains? visible "claim-user")))
    (testing "another role's contribution is hidden entirely"
      (is (not (contains? visible "claim-foreign"))))))

(deftest ^{:stratum 2} ablation-does-not-leak-through-conflicts
  (let [conflict (obj "conflict-1" :conflict :proposer
                      {:links {:contradicts #{"claim-own" "claim-foreign"}}})
        ws (workspace goal own-claim foreign-claim conflict)]
    (testing "under full visibility the conflict renders"
      (is (= ["conflict-1"]
             (ids (:projection/conflicts (projection/project ws :skeptic {}))))))
    (testing "a conflict naming a hidden object is withheld, not summarised"
      (let [p (projection/project ws :skeptic {:visibility :none})]
        (is (empty? (:projection/conflicts p)))
        (is (not (contains? (set (ids (:projection/objects p))) "conflict-1")))))))

(deftest ^{:stratum 2} a-visible-conflict-naming-a-hidden-object-is-withheld-entirely
  (testing "the leak is through the object list and delta, not just conflicts"
    (let [conflict (obj "conflict-1" :conflict :skeptic
                        {:links {:contradicts #{"claim-own" "claim-foreign"}}
                         :touched-at 11})
          ws (workspace own-claim foreign-claim conflict)
          p (projection/project ws :skeptic {:visibility :none :since 10})]
      (is (empty? (:projection/conflicts p)))
      (is (not (contains? (set (ids (:projection/objects p))) "conflict-1"))
          "a conflict the role authored still leaks the hidden object's existence")
      (is (not (contains? (set (ids (:projection/delta p))) "conflict-1"))
          "and the delta is the second way that existence escapes"))))

(deftest ^{:stratum 2} an-interpreter-authored-conflict-is-withheld-the-same-way
  (let [conflict (obj "conflict-1" :conflict :interpreter
                      {:links {:contradicts #{"claim-own" "claim-foreign"}}})
        ws (workspace own-claim foreign-claim conflict)
        p (projection/project ws :skeptic {:visibility :none})]
    (is (not (contains? (set (ids (:projection/objects p))) "conflict-1")))))

(deftest ^{:stratum 2} conflicts-render-when-every-referenced-object-is-visible
  (let [conflict (obj "conflict-1" :conflict :skeptic
                      {:links {:contradicts #{"claim-own"}}})
        p (projection/project (workspace own-claim conflict) :skeptic
                              {:visibility :none})]
    (is (= ["conflict-1"] (ids (:projection/conflicts p))))))

(deftest ^{:stratum 2} projection-is-deterministic
  (let [ws (workspace goal hard own-claim foreign-claim)]
    (is (= (projection/project ws :skeptic {})
           (projection/project ws :skeptic {})))
    (testing "ordering does not depend on map iteration order"
      (let [shuffled (workspace foreign-claim hard own-claim goal)]
        (is (= (ids (:projection/objects (projection/project ws :skeptic {})))
               (ids (:projection/objects (projection/project shuffled :skeptic {})))))))))

(deftest ^{:stratum 2} projection-reports-the-version-it-was-rendered-from
  (let [p (projection/project (workspace goal) :skeptic {})]
    (is (= 12 (:projection/version p)))
    (is (= :full (:projection/visibility p)))))
