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
(ns ai.miniforge.policy-pack.signing-fixtures
  "Ed25519 key generation and pack signing for signature tests.

   Real keys, real signatures: a test that stubs the crypto cannot tell a
   verifier that resolves the right key from one that accepts whatever the
   pack hands it. Keys are generated per run rather than checked in, so the
   suite covers both x parities of the Ed25519 public key encoding."
  (:require
   [ai.miniforge.policy-pack.canonical-edn :as canonical-edn]
   [ai.miniforge.policy-pack.crypto :as crypto])
  (:import
   [java.security KeyPairGenerator Signature]
   [java.util Base64]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private x-odd-bit
  "Bit carrying the sign of x in the final byte of a raw Ed25519 key."
  0x80)

(defn ^{:stratum 0} sign-pack
  "Sign a pack's canonical bytes (N4 §8.1.1) with `keypair`, returning the
   pack carrying :pack/signature and the given :pack/signed-by identifier."
  [pack keypair key-id]
  (let [signer (doto (Signature/getInstance "Ed25519")
                 (.initSign (:private keypair))
                 (.update ^bytes (canonical-edn/pack-signable-bytes pack)))]
    (assoc pack
           :pack/signature (.encodeToString (Base64/getEncoder) (.sign signer))
           :pack/signed-by key-id)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} generate-keypair
  "Generate an Ed25519 keypair.

   Returns {:private PrivateKey :public-key-base64 string :x-odd? bool},
   where the base64 holds the raw 32-byte public key a trust root carries —
   the trailing bytes of the X.509 SubjectPublicKeyInfo encoding — and
   :x-odd? reports the parity the raw encoding stores in its final bit."
  []
  (let [pair     (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded  ^bytes (.getEncoded (.getPublic pair))
        raw      (java.util.Arrays/copyOfRange encoded
                                               (- (alength encoded)
                                                  crypto/ed25519-public-key-bytes)
                                               (alength encoded))
        top-byte (aget raw (dec crypto/ed25519-public-key-bytes))]
    {:private (.getPrivate pair)
     :public-key-base64 (.encodeToString (Base64/getEncoder) raw)
     :x-odd? (pos? (bit-and top-byte x-odd-bit))}))
