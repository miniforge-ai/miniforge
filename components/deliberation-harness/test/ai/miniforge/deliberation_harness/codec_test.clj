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
  (doseq [text ["{:a 1} #custom/tag 2" "{:a 1} {" "{:a 1} {:b 2}" ""]]
    (is (anomaly/anomaly? (codec/read-text text)) (str "refused: " (pr-str text)))))

(deftest ^{:stratum 1} plain-data-cannot-forge-the-tag-marker
  (let [value {:ai.miniforge.deliberation-harness.codec/rejected-tag 'x}]
    (is (= value (codec/read-text (codec/encode value))))))

(deftest ^{:stratum 1} encoding-ignores-the-callers-printer-settings
  (let [value {:a [1 2 3] :b {:c {:d 1}} :e/f 1 :e/g 2}
        expected (codec/encode value)]
    (binding [*print-length* 1 *print-level* 1 *print-namespace-maps* true]
      (is (= expected (codec/encode value))))
    (is (= value (codec/read-text expected)))))

(deftest ^{:stratum 1} plain-values-round-trip
  (is (codec/plain? {:a [1 "x" #{:k}] :b nil}))
  (is (not (codec/plain? {:at (java.time.Instant/now)})))
  (testing "a float replays as the exact double it widens to"
    (is (codec/plain? {:x (float 1.2)}))))

(deftest ^{:stratum 1} a-tag-inside-anomaly-shaped-text-is-refused
  (is (= :invalid-input
         (:anomaly/type (codec/read-text
                         (str "{:anomaly/type :fault :anomaly/message \"m\""
                              " :anomaly/data {:x #custom/tag 1} :anomaly/at #inst \"2026-01-01\"}"))))))

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

(deftest ^{:stratum 1} encoding-never-drops-an-entry
  (testing "unequal float/double look-alikes are both written, with distinct texts"
    (doseq [value [(hash-set (float 1.2) (double 1.2))
                   (hash-set [(float 1.2)] [(double 1.2)])
                   (hash-map (float 1.2) :f (double 1.2) :d)]]
      (is (re-find #"1\.2000000476837158" (codec/encode value)))
      (is (re-find #"[ \[{#]1\.2[ \]}]" (codec/encode value)))))
  (testing "a look-alike tie still encodes deterministically"
    (is (= (codec/encode (hash-map [(float 1.2)] :a [(double 1.2)] :b))
           (codec/encode (hash-map [(double 1.2)] :b [(float 1.2)] :a))))))

(deftest ^{:stratum 1} equal-keys-are-one-entry
  (is (= (codec/encode {1 :v}) (codec/encode (assoc {1 :v} 1 :v))))
  (is (= (codec/encode {1.5 :f}) (codec/encode {(float 1.5) :f}))))

(deftest ^{:stratum 1} nil-and-ordering-are-plain
  (is (codec/plain? {nil :value :a 1}))
  (is (codec/plain? #{nil :a}))
  (is (= "{[1 2 3] :long [1 2] :short}" (codec/encode {[1 2] :short [1 2 3] :long}))))

(defrecord ^:private Probe [a])

(deftest ^{:stratum 1} digests-never-collide-across-types
  (testing "a record never digests like the plain map of its fields"
    (is (not= (codec/digest (->Probe 1)) (codec/digest {:a 1})))
    (is (not (codec/plain? (->Probe 1)))))
  (testing "a float never digests like a different double"
    (is (not= (codec/digest (float 1.2)) (codec/digest 1.2)))
    (is (= (codec/digest (float 1.5)) (codec/digest 1.5)))))

(deftest ^{:stratum 1} host-collections-never-digest-like-plain-ones
  (is (not= (codec/digest (java.util.ArrayList. [1 2])) (codec/digest [1 2])))
  (is (not= (codec/digest (java.util.HashMap. {:a 1})) (codec/digest {:a 1})))
  (is (not= (codec/digest (long-array [1 2])) (codec/digest [1 2])))
  (is (not (codec/plain? (java.util.ArrayList. [1 2])))))

(deftest ^{:stratum 1} non-edn-values-are-refused-before-encoding
  (is (not (codec/plain? {:x (doto (java.util.ArrayList.) (#(.add % %)))})))
  (is (not (codec/plain? [(reify Object (toString [_] (throw (ex-info "no" {}))))]))))

(deftest ^{:stratum 1} host-collections-encode-their-own-entries
  (testing "a host map keeps keys Clojure would merge"
    (let [linked (doto (java.util.LinkedHashMap.) (.put (int 1) :i) (.put (long 1) :l))]
      (is (= 2 (count (re-seq #":[il]" (codec/encode linked)))))))
  (testing "unequal host collections whose members print alike never share a digest"
    (is (not= (codec/digest (doto (java.util.HashMap.) (.put (int 1) :a) (.put 1 :b)))
              (codec/digest (doto (java.util.HashMap.) (.put (int 1) :b) (.put 1 :a)))))
    (is (not= (codec/digest (java.util.HashMap. {:a (int 1)})) (codec/digest (java.util.HashMap. {:a 1}))))
    (is (not= (codec/digest (java.util.ArrayList. [(int 1)])) (codec/digest (java.util.ArrayList. [1])))))
  (testing "equal host sets digest alike whatever their insertion order"
    (is (= (codec/digest (doto (java.util.LinkedHashSet.) (.add :a) (.add :b)))
           (codec/digest (doto (java.util.LinkedHashSet.) (.add :b) (.add :a)))))))

(deftest ^{:stratum 1} strings-utf8-cannot-carry-never-collide-or-journal
  (is (not= (codec/digest "\uD800") (codec/digest "\uDBFF")))
  (is (not (codec/plain? {:s "\uD800"}))))
