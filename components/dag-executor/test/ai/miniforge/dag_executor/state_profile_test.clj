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

(ns ai.miniforge.dag-executor.state-profile-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.state-profile :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- raw-profile
  "Build a raw (un-normalized) profile map suitable for build-profile."
  [profile-id & {:as overrides}]
  (merge {:profile/id                profile-id
          :task-statuses             [:pending :ready :running :done]
          :terminal-statuses         [:done :failed]
          :success-terminal-statuses [:done]
          :valid-transitions         {:pending [:ready]
                                      :ready   [:running]
                                      :running [:done :failed]
                                      :done    []
                                      :failed  []}
          :event-mappings            {:start    {:type :transition :to :running}
                                      :complete {:type :complete :to :done}}}
         overrides))

(defn- raw-provider
  "Provider map with inline (map) profile definitions."
  [& {:as overrides}]
  (merge {:default-profile :testing
          :profiles        {:testing (raw-profile :testing)
                            :other   (raw-profile :other)}}
         overrides))

;------------------------------------------------------------------------------ Layer 1
;; build-profile — set-coercion, transition-target sets, terminal-difference

(deftest build-profile-coerces-collections-to-sets-test
  (testing "task-statuses, terminal-statuses, success-terminal-statuses become sets"
    (let [p (sut/build-profile (raw-profile :p
                                            :task-statuses [:a :a :b]
                                            :terminal-statuses [:b]
                                            :success-terminal-statuses [:b]))]
      (is (= #{:a :b} (:task-statuses p)))
      (is (= #{:b}    (:terminal-statuses p)))
      (is (= #{:b}    (:success-terminal-statuses p))))))

(deftest build-profile-coerces-transition-targets-to-sets-test
  (testing "Each value in valid-transitions becomes a set of target statuses"
    (let [p (sut/build-profile (raw-profile :p
                                            :valid-transitions {:a [:b :b :c]
                                                                :b [:c]}))]
      (is (= #{:b :c} (get-in p [:valid-transitions :a])))
      (is (= #{:c}    (get-in p [:valid-transitions :b]))))))

(deftest build-profile-derives-failure-terminal-statuses-test
  (testing "failure-terminal-statuses = terminal-statuses minus success-terminal-statuses"
    (let [p (sut/build-profile (raw-profile :p
                                            :terminal-statuses [:done :failed :skipped]
                                            :success-terminal-statuses [:done]))]
      (is (= #{:failed :skipped} (:failure-terminal-statuses p))))))

(deftest build-profile-preserves-profile-id-and-event-mappings-test
  (testing "profile/id and event-mappings pass through unchanged"
    (let [events {:s {:type :transition :to :running}}
          p (sut/build-profile (raw-profile :pid :event-mappings events))]
      (is (= :pid    (:profile/id p)))
      (is (= events  (:event-mappings p))))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-provider-profile — string vs map vs nil

(deftest resolve-provider-profile-with-map-uses-supplied-id-test
  (testing "When the definition is a map without :profile/id, the supplied id is used"
    (let [[id profile] (sut/resolve-provider-profile :alpha
                                                     (-> (raw-profile :ignored)
                                                         (dissoc :profile/id)))]
      (is (= :alpha id))
      (is (= :alpha (:profile/id profile))))))

(deftest resolve-provider-profile-map-preserves-explicit-id-test
  (testing "When the map definition carries :profile/id, that id wins over the key"
    (let [[id profile] (sut/resolve-provider-profile :alpha (raw-profile :explicit))]
      (is (= :alpha id))
      (is (= :explicit (:profile/id profile))))))

(deftest resolve-provider-profile-string-resolves-classpath-resource-test
  (testing "A string definition is read via read-edn-resource and built"
    (let [[id profile] (sut/resolve-provider-profile
                         :kernel
                         "config/dag-executor/state-profiles/kernel.edn")]
      (is (= :kernel id))
      (is (= :kernel (:profile/id profile)))
      (is (contains? (:task-statuses profile) :pending))
      (is (set? (:terminal-statuses profile))))))

(deftest resolve-provider-profile-nil-for-unknown-resource-test
  (testing "Missing classpath resource → nil tuple"
    (is (nil? (sut/resolve-provider-profile
                :missing
                "config/dag-executor/state-profiles/does-not-exist.edn")))))

(deftest resolve-provider-profile-rejects-non-map-non-string-test
  (testing "Numbers, vectors, etc. are not valid profile definitions → nil"
    (is (nil? (sut/resolve-provider-profile :weird 42)))
    (is (nil? (sut/resolve-provider-profile :weird [1 2 3])))))

;------------------------------------------------------------------------------ Layer 1
;; build-provider — fallback id selection, profile normalization

(deftest build-provider-normalizes-each-profile-test
  (testing "Every profile in :profiles is run through build-profile"
    (let [provider (sut/build-provider (raw-provider))
          testing-p (get-in provider [:profiles :testing])]
      (is (set? (:task-statuses testing-p)))
      (is (set? (:terminal-statuses testing-p))))))

(deftest build-provider-default-profile-honors-supplied-id-test
  (testing "Supplied :default-profile is preserved"
    (is (= :testing (:default-profile (sut/build-provider (raw-provider)))))))

(deftest build-provider-default-falls-back-to-first-profile-test
  (testing "When :default-profile is absent, the first profile id is used"
    (let [provider (sut/build-provider {:profiles {:only (raw-profile :only)}})]
      (is (= :only (:default-profile provider))))))

(deftest build-provider-default-falls-back-to-kernel-when-no-profiles-test
  (testing "When neither :default-profile nor :profiles are supplied,
            the default-profile id falls back to :kernel"
    (let [provider (sut/build-provider {})]
      (is (= :kernel (:default-profile provider)))
      (is (= {} (:profiles provider))))))

;------------------------------------------------------------------------------ Layer 1
;; default-provider, available-profile-ids, default-profile-id

(deftest default-provider-returns-kernel-test
  (testing "default-provider includes the kernel profile"
    (let [provider (sut/default-provider)]
      (is (= :kernel (:default-profile provider)))
      (is (contains? (:profiles provider) :kernel)))))

(deftest available-profile-ids-default-and-explicit-test
  (testing "available-profile-ids reads from default-provider when no arg is given"
    (is (some #{:kernel} (sut/available-profile-ids))))
  (testing "available-profile-ids accepts a custom provider map"
    (is (= #{:testing :other}
           (set (sut/available-profile-ids (raw-provider)))))))

(deftest default-profile-id-test
  (testing "default-profile-id reads from default-provider when no arg is given"
    (is (= :kernel (sut/default-profile-id))))
  (testing "default-profile-id accepts a custom provider map"
    (is (= :testing (sut/default-profile-id (raw-provider))))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-profile — keyword / map / nil dispatch

(deftest resolve-profile-nil-returns-default-test
  (testing "Nil profile resolves to the provider's default profile"
    (let [resolved (sut/resolve-profile (raw-provider) nil)]
      (is (= :testing (:profile/id resolved))))))

(deftest resolve-profile-known-keyword-returns-that-profile-test
  (testing "A known keyword id returns the matching profile"
    (let [resolved (sut/resolve-profile (raw-provider) :other)]
      (is (= :other (:profile/id resolved))))))

(deftest resolve-profile-unknown-keyword-falls-back-to-default-test
  (testing "An unknown keyword id falls back to the provider's default"
    (let [resolved (sut/resolve-profile (raw-provider) :nope)]
      (is (= :testing (:profile/id resolved))))))

(deftest resolve-profile-with-map-builds-inline-test
  (testing "A map argument is run through build-profile and returned directly"
    (let [resolved (sut/resolve-profile (raw-provider) (raw-profile :inline))]
      (is (= :inline (:profile/id resolved)))
      (is (set? (:task-statuses resolved))))))

(deftest resolve-profile-default-overload-uses-default-provider-test
  (testing "The single-arity overload uses the default provider"
    (let [resolved (sut/resolve-profile :kernel)]
      (is (= :kernel (:profile/id resolved))))))

(deftest default-profile-resolves-via-default-provider-test
  (testing "default-profile (no args) returns the default provider's default profile"
    (is (= :kernel (:profile/id (sut/default-profile))))))
