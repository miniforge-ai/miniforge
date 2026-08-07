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
  "Ed25519 signature verification for policy packs. The bytes it verifies
   come from `policy-pack.canonical-edn` (N4 §8.1.1).

   Uses reflection for all Ed25519 class references to avoid direct
   imports of Java 15+ classes (EdECPublicKeySpec, NamedParameterSpec)
   that are not available in GraalVM/Babashka native-image.

   Layer 0: Key-encoding constants
   Layer 1: Raw public-key decoding
   Layer 2: Signature verification"
  (:require
   [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/policy-pack/messages/system.edn"
                              :policy-pack/system))

(def ^{:stratum 0} ed25519-public-key-bytes
  "Length of a raw Ed25519 public key, per RFC 8032 §5.1.5."
  32)

(def ^{:stratum 0} ^:private x-odd-bit
  "Bit carrying the sign of x in the final byte of a raw Ed25519 key."
  0x80)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} raw-ed25519-point
  "Decode a raw 32-byte Ed25519 public key into `[x-odd? y]`.

   RFC 8032 §5.1.2 stores y LITTLE-endian with the sign of x in the top bit
   of the final byte. Reading the bytes as one big-endian magnitude rejects
   every standards-generated key; taking x as always-even rejects half."
  [^bytes pub-key-bytes]
  (let [little-endian (aclone pub-key-bytes)
        last-index    (dec ed25519-public-key-bytes)
        top-byte      (Byte/toUnsignedInt (aget little-endian last-index))
        x-odd?        (pos? (bit-and top-byte x-odd-bit))]
    (aset-byte little-endian last-index
               (unchecked-byte (bit-and top-byte (bit-not x-odd-bit))))
    [x-odd? (java.math.BigInteger. 1 (byte-array (reverse (seq little-endian))))]))

;------------------------------------------------------------------------------ Layer 2

;; Ed25519 verification via reflection
(defn ^{:stratum 2} verify-ed25519
  "Verify an Ed25519 signature against content bytes.

   Arguments:
   - content-bytes — byte array of the signable content
   - sig-bytes     — byte array of the signature
   - pub-key-bytes — raw 32-byte Ed25519 public key, per RFC 8032 §5.1.5

   Returns:
   - {:verified? true} on success
   - {:verified? false :reason string} on every failure, a signature that
     simply does not verify included, so a caller always has something to
     log"
  [content-bytes sig-bytes ^bytes pub-key-bytes]
  (try
    (if (or (nil? pub-key-bytes)
            (not= ed25519-public-key-bytes (alength pub-key-bytes)))
      {:verified? false :reason (t :crypto/bad-public-key-length)}
      (let [named-spec (Class/forName "java.security.spec.NamedParameterSpec")
            edec-point (Class/forName "java.security.spec.EdECPoint")
            edec-spec  (Class/forName "java.security.spec.EdECPublicKeySpec")
            param      (.getConstructor named-spec (into-array Class [String]))
            ed-param   (.newInstance param (into-array Object ["Ed25519"]))
            point-ctor (.getConstructor edec-point (into-array Class [Boolean/TYPE java.math.BigInteger]))
            [x-odd? y] (raw-ed25519-point pub-key-bytes)
            point      (.newInstance point-ctor (into-array Object [x-odd? y]))
            spec-ctor  (.getConstructor edec-spec (into-array Class [named-spec edec-point]))
            key-spec   (.newInstance spec-ctor (into-array Object [ed-param point]))
            kf         (java.security.KeyFactory/getInstance "EdDSA")
            public-key (.generatePublic kf key-spec)
            verifier   (doto (java.security.Signature/getInstance "Ed25519")
                         (.initVerify public-key)
                         (.update ^bytes content-bytes))]
        (if (.verify verifier sig-bytes)
          {:verified? true}
          {:verified? false :reason (t :crypto/invalid-signature)})))
    (catch Exception e
      ;; .getMessage is nil for some exceptions (a bare NPE among them), and
      ;; a nil reason is the one thing this fn promises never to return.
      {:verified? false :reason (or (.getMessage e) (t :crypto/verification-error))})))
