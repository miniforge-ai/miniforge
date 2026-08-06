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
(ns ai.miniforge.policy-pack.registry-signature-test
  "Tests for pack signature verification against configured trust roots
   (N4 §8.2).

   The case that motivates the whole store is `self-certifying-pack-test`:
   a pack that carries its own verification key must fail, however
   internally consistent its signature is."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.registry :as sut]
   [ai.miniforge.policy-pack.signing-fixtures :as fixtures]
   [ai.miniforge.policy-pack.trust-roots :as trust-roots]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private unsigned-pack
  {:pack/id "acme/security"
   :pack/name "Acme Security"
   :pack/version "2026.08.05"
   :pack/description "A pack under signature test"
   :pack/author "acme"
   :pack/categories [{:category/id "300"
                      :category/name "Infrastructure"
                      :category/rules [:no-public-buckets]}]
   :pack/rules [{:rule/id :no-public-buckets
                 :rule/title "No public buckets"
                 :rule/description "Buckets must not be world-readable"
                 :rule/severity :critical
                 :rule/category "300"
                 :rule/applies-to {:phases #{:implement :review}}
                 :rule/detection {:type :content-scan :pattern "acl = \"public-read\""}
                 :rule/enforcement {:action :hard-halt :message "Public bucket"}}]
   :pack/created-at #inst "2026-08-01T00:00:00.000Z"
   :pack/updated-at #inst "2026-08-04T00:00:00.000Z"})

(defn- ^{:stratum 0} registry-trusting
  "A registry whose trust roots hold exactly the given identifier→keypair
   pairs."
  [& id+keypairs]
  (sut/create-registry
   {:trust-roots (trust-roots/->store
                  (mapv (fn [[key-id keypair]]
                          {:trust-root/key-id key-id
                           :trust-root/public-key (:public-key-base64 keypair)})
                        (partition 2 id+keypairs)))}))

;; ============================================================================
;; Key encoding
;; ============================================================================
(def ^{:stratum 0} ^:private parity-draw-limit
  "Keypairs to draw looking for one of each x parity. Each draw is an
   independent coin flip, so missing a parity in this many is ~1e-19."
  64)

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; The trusted path
;; ============================================================================
(deftest ^{:stratum 1} trusted-signature-verifies-test
  (let [publisher (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        pack      (fixtures/sign-pack unsigned-pack publisher "acme-publisher-2026")
        result    (sut/verify-signature registry pack)]

    (testing "a pack signed by a trusted publisher verifies"
      (is (:verified? result)))

    (testing "the result reports the resolved signer"
      (is (= "acme-publisher-2026" (:signer result))))))

(deftest ^{:stratum 1} trusted-signature-covers-content-test
  (let [publisher (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        pack      (fixtures/sign-pack unsigned-pack publisher "acme-publisher-2026")]

    (testing "editing a rule after signing invalidates the signature"
      (is (not (:verified?
                (sut/verify-signature
                 registry
                 (assoc-in pack [:pack/rules 0 :rule/enforcement :action] :warn))))))

    (testing "editing pack metadata after signing invalidates the signature"
      (is (not (:verified?
                (sut/verify-signature
                 registry
                 (assoc pack :pack/author "mallory"))))))))

;; ============================================================================
;; Untrusted paths
;; ============================================================================
(deftest ^{:stratum 1} unknown-key-identifier-test
  (let [publisher (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        pack      (fixtures/sign-pack unsigned-pack publisher "who-is-this")
        result    (sut/verify-signature registry pack)]

    (testing "a signer identifier absent from the trust roots fails"
      (is (not (:verified? result))))

    (testing "the failure names the unresolved signer, not a crypto fault"
      (is (= "who-is-this" (:signer result)))
      (is (= "Pack signer is not in the configured trust roots" (:reason result))))))

(deftest ^{:stratum 1} untrusted-signer-test
  (let [publisher (fixtures/generate-keypair)
        attacker  (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        ;; Internally consistent: the attacker re-signed the pack they
        ;; modified, then claimed the publisher's identifier.
        pack      (fixtures/sign-pack (assoc unsigned-pack :pack/author "mallory")
                                      attacker
                                      "acme-publisher-2026")]

    (testing "a pack re-signed with an untrusted key fails under a trusted identifier"
      (is (not (:verified? (sut/verify-signature registry pack)))))))

(deftest ^{:stratum 1} self-certifying-pack-test
  (let [publisher (fixtures/generate-keypair)
        attacker  (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        ;; The pre-§8.2.1 attack: modify the pack, sign it with your own
        ;; key, and write that public key into :pack/signed-by. Verification
        ;; used to decode the field as key material and pass.
        pack      (fixtures/sign-pack (assoc unsigned-pack :pack/author "mallory")
                                      attacker
                                      (:public-key-base64 attacker))
        result    (sut/verify-signature registry pack)]

    (testing "a pack that supplies its own verification key fails"
      (is (not (:verified? result))))

    (testing "the field is treated as an identifier, so it resolves to nothing"
      (is (= "Pack signer is not in the configured trust roots" (:reason result))))

    (testing "the signature is genuinely self-consistent — only trust rejects it"
      (is (:verified?
           (sut/verify-signature (registry-trusting (:public-key-base64 attacker)
                                                    attacker)
                                 pack))))))

(deftest ^{:stratum 1} empty-trust-roots-test
  (let [publisher (fixtures/generate-keypair)
        registry  (sut/create-registry {:trust-roots {}})
        pack      (fixtures/sign-pack unsigned-pack publisher "acme-publisher-2026")]

    (testing "with nothing configured, no signed pack verifies"
      (is (not (:verified? (sut/verify-signature registry pack)))))))

;; ============================================================================
;; Malformed signature fields
;; ============================================================================
(deftest ^{:stratum 1} malformed-signature-fields-test
  (let [publisher (fixtures/generate-keypair)
        registry  (registry-trusting "acme-publisher-2026" publisher)
        pack      (fixtures/sign-pack unsigned-pack publisher "acme-publisher-2026")]

    (testing "an unsigned pack is reported as unsigned, with no claimed signer"
      (let [result (sut/verify-signature registry unsigned-pack)]
        (is (not (:verified? result)))
        (is (= "Pack is not signed" (:reason result)))
        (is (not (contains? result :signer)))
        (is (not (contains? result :timestamp)))))

    (testing "a signed pack reports its claimed signer and timestamp"
      (let [result (sut/verify-signature
                    registry
                    (assoc pack :pack/signed-at #inst "2026-08-05T00:00:00.000Z"))]
        (is (= "acme-publisher-2026" (:signer result)))
        (is (= #inst "2026-08-05T00:00:00.000Z" (:timestamp result)))))

    (testing "a signature with no key identifier fails"
      (let [result (sut/verify-signature registry (dissoc pack :pack/signed-by))]
        (is (not (:verified? result)))
        (is (= "Pack carries a signature but no :pack/signed-by key identifier"
               (:reason result)))))

    (testing "a signature that is not base64 fails rather than throwing"
      (let [result (sut/verify-signature registry
                                         (assoc pack :pack/signature "not base64!"))]
        (is (not (:verified? result)))
        (is (= "Pack signature is not valid base64" (:reason result)))))

    (testing "a signature that is not a string fails rather than throwing"
      ;; Verification runs on packs that have not necessarily been through
      ;; schema validation.
      (let [result (sut/verify-signature registry (assoc pack :pack/signature 42))]
        (is (not (:verified? result)))
        (is (= "Pack signature is not valid base64" (:reason result)))))

    (testing "a key identifier that is not a string fails"
      (let [result (sut/verify-signature registry (assoc pack :pack/signed-by 42))]
        (is (not (:verified? result)))
        (is (= "Pack carries a signature but no :pack/signed-by key identifier"
               (:reason result)))))

    (testing "a well-formed but wrong signature fails"
      (let [other  (fixtures/sign-pack (assoc unsigned-pack :pack/version "2026.08.06")
                                       publisher
                                       "acme-publisher-2026")
            result (sut/verify-signature registry
                                         (assoc pack :pack/signature
                                                (:pack/signature other)))]
        (is (not (:verified? result)))))))

(defn- ^{:stratum 1} keypair-of-each-parity
  "Draw keypairs until one of each x parity is in hand, or the limit runs out."
  []
  (reduce (fn [found _]
            (if (= 2 (count found))
              (reduced found)
              (let [keypair (fixtures/generate-keypair)]
                (update found (:x-odd? keypair) #(or % keypair)))))
          {}
          (range parity-draw-limit)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} both-x-parities-verify-test
  ;; The raw encoding carries the sign of x in the top bit of the last byte.
  ;; Taking x as always-even rejects about half of all real keys, so one
  ;; generated keypair is not enough to cover the decoder.
  (let [by-parity (keypair-of-each-parity)]
    (testing "both parities occur within the draw limit"
      (is (= #{true false} (set (keys by-parity)))))

    (doseq [[x-odd? publisher] by-parity]
      (testing (str "a key with x-odd? " x-odd? " verifies")
        (let [registry (registry-trusting "publisher" publisher)
              pack     (fixtures/sign-pack unsigned-pack publisher "publisher")]
          (is (:verified? (sut/verify-signature registry pack))))))))
