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
(ns ai.miniforge.utf8.core
  "Strict UTF-8 decoding that loads on every supported runtime.

   The JDK route to strict decoding is a CharsetDecoder set to REPORT
   malformed input. Babashka before 1.12.209 has neither CharsetDecoder
   nor CodingErrorAction, so a namespace that imports them cannot load
   there. This decodes with the String constructor, which replaces each
   malformed sequence with U+FFFD, and accepts the text only when
   encoding it again gives back the same bytes. Well-formed UTF-8
   round-trips exactly. Malformed input never does: the first malformed
   position re-encodes as EF BF BD, which is itself well-formed, so the
   input bytes there must differ."
  (:import
   [java.nio.charset StandardCharsets]
   [java.util Arrays]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode-strict
  "The text `bytes` encode as UTF-8, or nil when they are not well-formed
   UTF-8 (a malformed or truncated sequence, an overlong form, or an
   encoded surrogate)."
  [^bytes bytes]
  (let [text (String. bytes StandardCharsets/UTF_8)]
    (when (Arrays/equals bytes (.getBytes text StandardCharsets/UTF_8))
      text)))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  (decode-strict (.getBytes "héllo" StandardCharsets/UTF_8))
  (decode-strict (byte-array [(unchecked-byte 0xff) 65]))

  :leave-this-here)
