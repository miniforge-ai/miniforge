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
(ns ai.miniforge.deliberation-harness.codec-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.codec :as codec]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sha-256-hex-length 64)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} equal-values-encode-identically
  (testing "map and set construction order does not change the encoding"
    (is (= (codec/encode {:b #{3 1 2} :a {:z 1 :y 2}})
           (codec/encode {:a {:y 2 :z 1} :b #{2 3 1}}))))
  (testing "the digest is a stable hex SHA-256"
    (is (= sha-256-hex-length (count (codec/digest {:a 1}))))
    (is (= (codec/digest {:a 1 :b 2}) (codec/digest {:b 2 :a 1})))))

(deftest ^{:stratum 1} reading-accepts-plain-data-only
  (is (= {:a [1 "x"]} (codec/read-text "{:a [1 \"x\"]}")))
  (testing "a custom reader tag is refused, not evaluated"
    (is (anomaly/anomaly? (codec/read-text "#custom/tag {:x 1}")))
    (is (anomaly/anomaly? (codec/read-text "{:a [#custom/tag 1]}"))))
  (testing "unreadable text is an anomaly, not an exception"
    (is (anomaly/anomaly? (codec/read-text "{:a")))))

(deftest ^{:stratum 1} reading-takes-exactly-one-form
  (testing "trailing forms and malformed suffixes are refused"
    (is (anomaly/anomaly? (codec/read-text "{:a 1} #custom/tag 2")))
    (is (anomaly/anomaly? (codec/read-text "{:a 1} {")))
    (is (anomaly/anomaly? (codec/read-text "{:a 1} {:b 2}"))))
  (testing "empty text holds no form"
    (is (anomaly/anomaly? (codec/read-text "")))))

(deftest ^{:stratum 1} plain-data-cannot-forge-the-tag-marker
  (let [value {:ai.miniforge.deliberation-harness.codec/rejected-tag 'x}]
    (is (= value (codec/read-text (codec/encode value))))))

(deftest ^{:stratum 1} encoding-ignores-the-callers-printer-settings
  (let [value {:a [1 2 3] :b {:c {:d 1}} :e/f 1 :e/g 2}
        expected (codec/encode value)]
    (binding [*print-length* 1 *print-level* 1 *print-namespace-maps* true]
      (is (= expected (codec/encode value))))
    (is (= value (codec/read-text expected)))))

(deftest ^{:stratum 1} canonical-order-ignores-the-callers-printer-settings
  (let [ordered (codec/canonical {[1 2 3] :long [1 2] :short})]
    (binding [*print-length* 1]
      (is (= 3 (count (assoc ordered [1 9] :new)))))))

(deftest ^{:stratum 1} values-that-print-alike-stay-distinct
  (is (= 2 (count (codec/canonical (hash-set (float 1.2) (double 1.2))))))
  (is (= 2 (count (codec/canonical (hash-map (float 1.2) :f (double 1.2) :d))))))

(deftest ^{:stratum 1} plain-values-round-trip
  (is (codec/plain? {:a [1 "x" #{:k}] :b nil}))
  (is (not (codec/plain? {:at (java.time.Instant/now)})))
  (is (not (codec/plain? {:x (float 1.2)}))))

(deftest ^{:stratum 1} a-tag-inside-anomaly-shaped-text-is-refused
  (is (= :invalid-input
         (:anomaly/type (codec/read-text
                         (str "{:anomaly/type :fault :anomaly/message \"m\""
                              " :anomaly/data {:x #custom/tag 1} :anomaly/at #inst \"2026-01-01\"}"))))))

(deftest ^{:stratum 1} nil-keys-stay-usable-after-canonicalizing
  (is (= :value (get (codec/canonical {nil :value :a 1}) nil)))
  (is (contains? (codec/canonical #{nil :a}) nil)))

(deftest ^{:stratum 1} anomaly-shaped-data-is-data
  (let [text (str "{:anomaly/type :fault :anomaly/message \"m\""
                  " :anomaly/data {} :anomaly/at #inst \"2026-01-01T00:00:00.000-00:00\"}")
        value (codec/read-text text)]
    (is (= :fault (:anomaly/type value)))
    (is (not (contains? (:anomaly/data value) :source)))
    (is (codec/plain? value))))

(deftest ^{:stratum 1} host-maps-whose-order-canonicalizing-skips-are-not-plain
  (let [linked (doto (java.util.LinkedHashMap.) (.put :b 1) (.put :a 2))]
    (is (not (codec/plain? linked)))))

(deftest ^{:stratum 1} canonical-lookups-keep-clojure-key-equality
  (is (= :f (get (codec/canonical {(float 1.5) :f}) (double 1.5))))
  (is (contains? (codec/canonical #{(float 1.5)}) (double 1.5))))
