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
