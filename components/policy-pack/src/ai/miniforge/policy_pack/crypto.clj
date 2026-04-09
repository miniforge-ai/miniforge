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

(ns ai.miniforge.policy-pack.crypto
  "Cryptographic signature verification for policy packs.

   Uses reflection for all Ed25519 class references to avoid direct
   imports of Java 15+ classes (EdECPublicKeySpec, NamedParameterSpec)
   that are not available in GraalVM/Babashka native-image.

   Layer 0: Ed25519 signature verification
   Layer 1: Pack content hashing

   Note: If byte-level parsing expands beyond signature verification,
   consider adopting gloss for structured binary codec work.")

;------------------------------------------------------------------------------ Layer 0
;; Ed25519 verification via reflection

(defn verify-ed25519
  "Verify an Ed25519 signature against content bytes.

   Arguments:
   - content-bytes — byte array of the signable content
   - sig-bytes     — byte array of the signature
   - pub-key-bytes — byte array of the public key

   Returns:
   - {:verified? true} on success
   - {:verified? false :reason string} on failure or missing Java 15+ support"
  [content-bytes sig-bytes pub-key-bytes]
  (try
    (let [named-spec (Class/forName "java.security.spec.NamedParameterSpec")
          edec-point (Class/forName "java.security.spec.EdECPoint")
          edec-spec  (Class/forName "java.security.spec.EdECPublicKeySpec")
          param      (.getConstructor named-spec (into-array Class [String]))
          ed-param   (.newInstance param (into-array Object ["Ed25519"]))
          point-ctor (.getConstructor edec-point (into-array Class [Boolean/TYPE java.math.BigInteger]))
          point      (.newInstance point-ctor (into-array Object [false (java.math.BigInteger. 1 ^bytes pub-key-bytes)]))
          spec-ctor  (.getConstructor edec-spec (into-array Class [named-spec edec-point]))
          key-spec   (.newInstance spec-ctor (into-array Object [ed-param point]))
          kf         (java.security.KeyFactory/getInstance "EdDSA")
          public-key (.generatePublic kf key-spec)
          verifier   (doto (java.security.Signature/getInstance "Ed25519")
                       (.initVerify public-key)
                       (.update ^bytes content-bytes))]
      {:verified? (.verify verifier sig-bytes)})
    (catch Exception e
      {:verified? false :reason (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 1
;; Pack content hashing

(defn pack-signable-bytes
  "Compute the signable byte representation of a pack manifest.
   Strips signature fields and serializes the remainder as sorted EDN."
  [pack]
  (let [signable (dissoc pack :pack/signature :pack/signed-by :pack/signed-at)]
    (.getBytes (pr-str (into (sorted-map) signable)) "UTF-8")))
