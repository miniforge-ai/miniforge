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

(ns ai.miniforge.event-stream.digest
  "Content-digest utility for event payload summaries.

   Produces bounded, reproducible digest maps from arbitrary content so
   event constructors can attach structured summaries to tool-call and
   tool-result payloads without embedding raw (potentially large) content
   directly in the event log.

   All functions are pure — no IO, no side effects."
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const max-preview-bytes
  "Maximum UTF-8 byte length retained in :digest/preview."
  1024)

(def ^:const sha256-hex-length
  "Length of a SHA-256 digest rendered as lowercase hexadecimal."
  64)

(def ^:private byte-hex-format
  "Format string for rendering a byte as two lowercase hexadecimal chars."
  "%02x")

(def ^:private unsigned-byte-mask
  "Mask used to render signed JVM bytes as unsigned byte values."
  0xff)

(def ^:private sha256-algorithm
  "JCA algorithm name for SHA-256 digests."
  "SHA-256")

;------------------------------------------------------------------------------ Layer 0
;; Internal helpers

(defn- ->bytes
  "Coerce `content` to a byte array deterministically.

   - `byte[]`  — returned as-is
   - `String`  — UTF-8 encoded
   - anything else — `str`-coerced then UTF-8 encoded"
  ^bytes [content]
  (cond
    (bytes? content)  content
    (string? content) (.getBytes ^String content StandardCharsets/UTF_8)
    :else             (.getBytes ^String (str content) StandardCharsets/UTF_8)))

(defn- bytes->hex
  "Convert a byte array to a lowercase hexadecimal string."
  [^bytes ba]
  (apply str (map #(format byte-hex-format
                           (bit-and (int %) unsigned-byte-mask))
                  ba)))

(defn- sha256-hex
  "Return the SHA-256 digest of `ba` as a lowercase hex string."
  [^bytes ba]
  (let [^MessageDigest md (MessageDigest/getInstance sha256-algorithm)]
    (bytes->hex (.digest md ba))))

(defn- utf8-preview
  "Return the longest Unicode-safe prefix whose UTF-8 byte count fits
   within `max-bytes`."
  [^String s max-bytes]
  (let [length (.length s)]
    (loop [idx        0
           byte-count 0
           out        (StringBuilder.)]
      (if (>= idx length)
        (str out)
        (let [code-point (.codePointAt s idx)
              chars      (String/valueOf (Character/toChars code-point))
              char-count (Character/charCount code-point)
              next-bytes (alength (.getBytes chars StandardCharsets/UTF_8))]
          (if (> (+ byte-count next-bytes) max-bytes)
            (str out)
            (do
              (.append out chars)
              (recur (+ idx char-count)
                     (+ byte-count next-bytes)
                     out))))))))

;------------------------------------------------------------------------------ Layer 1
;; Public API

(defn digest-content
  "Produce a bounded digest map from arbitrary content.

   Accepts strings, byte arrays, or any value (coerced via `str`).

   Returns:
     {:digest/preview       — Unicode-safe UTF-8 prefix capped at
                              `max-preview-bytes`
      :digest/sha256        — lowercase hex SHA-256 of the
                              UTF-8-encoded content
      :digest/original-size — byte length of the UTF-8 representation}

   Pure: no IO, no external calls, no mutable state."
  [content]
  (let [ba       (->bytes content)
        size     (alength ba)
        as-str   (if (string? content)
                   content
                   (String. ^bytes ba StandardCharsets/UTF_8))
        preview  (utf8-preview as-str max-preview-bytes)]
    {:digest/preview       preview
     :digest/sha256        (sha256-hex ba)
     :digest/original-size size}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick smoke-tests at the REPL

  (digest-content "hello")
  ;; => {:digest/preview "hello"
  ;;     :digest/sha256 "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
  ;;     :digest/original-size 5}

  (digest-content {:tool/name "Read" :tool/args {:file_path "/foo/bar.clj"}})

  ;; Large payload — preview is capped at max-preview-bytes.
  (let [big (apply str (repeat (* 2 max-preview-bytes) "x"))]
    (-> (digest-content big)
        (update :digest/preview count)))
  ;; => {:digest/preview max-preview-bytes ...}

  :leave-this-here)
