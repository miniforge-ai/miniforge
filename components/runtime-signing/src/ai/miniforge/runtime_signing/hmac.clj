;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.hmac
  "HMAC-SHA256 over the lossless canonical encoding of a value."
  (:require
   [ai.miniforge.runtime-signing.canonical :as canonical])
  (:import
   [java.nio CharBuffer]
   [java.nio.charset CodingErrorAction StandardCharsets]
   [java.security MessageDigest]
   [javax.crypto Mac]
   [javax.crypto.spec SecretKeySpec]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} algorithm "HmacSHA256")

(defn ^{:stratum 0} hex
  "Lowercase hex of a byte array."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn- ^{:stratum 0} utf8
  "Strict UTF-8 bytes of `s`. Throws on malformed text, such as an
   unpaired surrogate, which `String.getBytes` would replace with `?`."
  ^bytes [^String s]
  (let [encoder (-> StandardCharsets/UTF_8 .newEncoder
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
        buffer (.encode encoder (CharBuffer/wrap s))
        out (byte-array (.remaining buffer))]
    (.get buffer out)
    out))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sign
  "Hex HMAC-SHA256 of `value`'s canonical encoding under `key-bytes`.
   Throws when `value` cannot be encoded losslessly: signing it is a
   programmer error."
  [^bytes key-bytes value]
  (let [mac (Mac/getInstance algorithm)]
    (.init mac (SecretKeySpec. key-bytes algorithm))
    (hex (.doFinal mac (utf8 (canonical/encode value))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} signed?
  "True only when `signature` is the signature of `value` under `key-bytes`.
   Compared in constant time; never throws on untrusted input."
  [^bytes key-bytes value signature]
  (try
    (and (string? signature)
         (MessageDigest/isEqual (utf8 (sign key-bytes value)) (utf8 signature)))
    (catch Exception _ false)))

(comment
  (sign (byte-array 32) {:a 1}))
