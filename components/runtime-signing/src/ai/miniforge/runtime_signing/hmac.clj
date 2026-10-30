;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.hmac
  "HMAC-SHA256 over the canonical EDN of a value."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]
   [javax.crypto Mac]
   [javax.crypto.spec SecretKeySpec]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} algorithm "HmacSHA256")

(defn ^{:stratum 0} hex
  "Lowercase hex of a byte array."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn- ^{:stratum 0} utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sign
  "Hex HMAC-SHA256 of `value`'s canonical EDN under `key-bytes`. Printer
   settings are pinned, so a caller's `*print-length*` or namespace-map
   binding cannot change or truncate the signed text. Throws when `value`
   cannot be rendered canonically: signing it is a programmer error."
  [^bytes key-bytes value]
  (let [mac (Mac/getInstance algorithm)
        text (binding [*print-length* nil *print-level* nil *print-meta* false
                       *print-namespace-maps* false *print-dup* false]
               (content-hash/canonical-edn value))]
    (.init mac (SecretKeySpec. key-bytes algorithm))
    (hex (.doFinal mac (utf8 text)))))

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
