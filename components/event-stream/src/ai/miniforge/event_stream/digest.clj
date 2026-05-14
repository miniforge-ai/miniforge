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
  (:import [java.security MessageDigest]))

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
    (string? content) (.getBytes ^String content "UTF-8")
    :else             (.getBytes ^String (str content) "UTF-8")))

(defn- bytes->hex
  "Convert a byte array to a lowercase hexadecimal string."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) ba)))

(defn- sha256-hex
  "Return the SHA-256 digest of `ba` as a 64-character lowercase hex string."
  [^bytes ba]
  (let [^MessageDigest md (MessageDigest/getInstance "SHA-256")]
    (bytes->hex (.digest md ba))))

;------------------------------------------------------------------------------ Layer 1
;; Public API

(defn digest-content
  "Produce a bounded digest map from arbitrary content.

   Accepts strings, byte arrays, or any value (coerced via `str`).

   Returns:
     {:digest/preview       — first 1 024 characters of the string
                              representation (safe for event log)
      :digest/sha256        — 64-char lowercase hex SHA-256 of the
                              UTF-8-encoded content
      :digest/original-size — byte length of the UTF-8 representation}

   Pure: no IO, no external calls, no mutable state."
  [content]
  (let [ba       (->bytes content)
        size     (alength ba)
        as-str   (if (string? content)
                   content
                   (new String ^bytes ba "UTF-8"))
        preview  (subs as-str 0 (min 1024 (count as-str)))]
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

  ;; Large payload — preview is capped at 1024 chars
  (let [big (apply str (repeat 2000 "x"))]
    (-> (digest-content big)
        (update :digest/preview count)))
  ;; => {:digest/preview 1024 ...}

  :leave-this-here)
