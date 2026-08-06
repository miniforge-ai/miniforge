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
(ns ai.miniforge.policy-pack.trust-roots-test
  "Tests for the configured trusted-publisher store (N4 §8.2.1).

   Covers entry validation, store construction and precedence, identifier
   resolution, and the shipped-plus-operator config chain."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.signing-fixtures :as fixtures]
   [ai.miniforge.policy-pack.trust-roots :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} entry
  [key-id public-key-base64]
  {:trust-root/key-id key-id
   :trust-root/public-key public-key-base64})

(deftest ^{:stratum 0} decode-public-key-test
  (let [{:keys [public-key-base64]} (fixtures/generate-keypair)]
    (testing "a raw Ed25519 key decodes to 32 bytes"
      (is (= 32 (alength (sut/decode-public-key public-key-base64)))))

    (testing "non-base64 decodes to nil rather than throwing"
      (is (nil? (sut/decode-public-key "not base64!"))))

    (testing "base64 of the wrong length decodes to nil"
      (is (nil? (sut/decode-public-key "c2hvcnQ="))))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Entry validation
;; ============================================================================
(deftest ^{:stratum 1} validate-entry-test
  (let [{:keys [public-key-base64]} (fixtures/generate-keypair)]
    (testing "a well-formed entry validates"
      (is (:valid? (sut/validate-entry (entry "acme-2026" public-key-base64)))))

    (testing "an entry missing the key identifier fails"
      (is (not (:valid? (sut/validate-entry
                         {:trust-root/public-key public-key-base64})))))

    (testing "an entry missing the public key fails"
      (is (not (:valid? (sut/validate-entry {:trust-root/key-id "acme-2026"})))))

    (testing "a blank key identifier fails"
      (is (not (:valid? (sut/validate-entry (entry "" public-key-base64))))))

    (testing "a public key that is not base64 fails"
      (is (not (:valid? (sut/validate-entry (entry "acme-2026" "not base64!"))))))

    (testing "a base64 public key of the wrong length fails"
      (is (not (:valid? (sut/validate-entry (entry "acme-2026" "c2hvcnQ="))))))

    (testing "the key-material failure names the field and the expected length"
      (is (= {:trust-root/public-key
              ["must be base64 of a raw 32-byte Ed25519 public key"]}
             (:errors (sut/validate-entry (entry "acme-2026" "c2hvcnQ="))))))))

;; ============================================================================
;; Store construction and lookup
;; ============================================================================
(deftest ^{:stratum 1} ->store-test
  (let [alice (fixtures/generate-keypair)
        bob   (fixtures/generate-keypair)]
    (testing "entries are keyed by identifier"
      (let [store (sut/->store [(entry "alice" (:public-key-base64 alice))
                                (entry "bob" (:public-key-base64 bob))])]
        (is (= #{"alice" "bob"} (set (keys store))))))

    (testing "a later collection wins on a shared identifier"
      (let [store (sut/->store [(entry "alice" (:public-key-base64 alice))]
                               [(entry "alice" (:public-key-base64 bob))])]
        (is (= (:public-key-base64 bob)
               (get-in store ["alice" :trust-root/public-key])))))

    (testing "no publishers yields an empty store"
      (is (= {} (sut/->store [] nil))))

    (testing "a malformed entry throws rather than being dropped"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/->store [(entry "alice" "not base64!")]))))))

(deftest ^{:stratum 1} resolve-key-test
  (let [alice (fixtures/generate-keypair)
        store (sut/->store [(entry "alice" (:public-key-base64 alice))])]
    (testing "a configured identifier resolves to its key bytes"
      (is (= 32 (alength (sut/resolve-key store "alice")))))

    (testing "an unknown identifier resolves to nothing"
      (is (nil? (sut/resolve-key store "mallory"))))

    (testing "an absent or blank identifier resolves to nothing"
      (is (nil? (sut/resolve-key store nil)))
      (is (nil? (sut/resolve-key store ""))))

    (testing "an empty store resolves nothing — the fail-closed default"
      (is (nil? (sut/resolve-key {} "alice"))))))
