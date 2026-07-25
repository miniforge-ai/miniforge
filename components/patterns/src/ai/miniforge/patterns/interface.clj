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
(ns ai.miniforge.patterns.interface
  "Centralized, named regex patterns for reuse across components.

   Instead of inline regex literals, require this namespace and
   reference patterns by name:

     (re-find patterns/md-heading-file-path line)
     (re-find patterns/rate-limit response-text)"
  (:require [ai.miniforge.patterns.core :as core]))

;------------------------------------------------------------------------------ Layer 0

;; Markdown / file-path extraction
(def ^{:stratum 0} md-heading-file-path
  "java.util.regex.Pattern matching a file path inside a markdown heading,
   e.g. `### path/to/file.clj` (1-4 leading #, optional ` or * wrapping).
   Capture group 1 holds the bare path. Use with re-find: returns
   [whole-match path] vector on a hit, nil on no match."
  core/md-heading-file-path)

(def ^{:stratum 0} md-delimited-file-path
  "java.util.regex.Pattern matching a file path wrapped in backticks or
   bold/italic asterisks, e.g. `` `path/to/file.clj` `` or `**path/to/file.clj**`.
   Capture group 1 holds the bare path. Use with re-find: returns
   [whole-match path] vector on a hit, nil on no match."
  core/md-delimited-file-path)

(def ^{:stratum 0} md-label-file-path
  "java.util.regex.Pattern (case-insensitive) matching a labelled file path,
   e.g. `File: path/to/file.clj` or `Path: path/to/file.clj` (optional
   backticks). Capture group 1 holds the bare path. Use with re-find:
   returns [whole-match path] vector on a hit, nil on no match."
  core/md-label-file-path)

(def ^{:stratum 0} md-code-block
  "java.util.regex.Pattern matching a fenced markdown code block:
   ```lang\\ncontent```. Capture group 1 holds the optional language tag
   (nil if absent), group 2 the block body. Use with re-find/re-seq:
   returns [whole-match lang body] vectors, nil/empty on no match."
  core/md-code-block)

;; File extensions
(def ^{:stratum 0} file-extension
  "java.util.regex.Pattern matching a trailing file extension, e.g. `.clj`.
   Capture group 1 holds the extension without the dot. Use with re-find:
   returns [whole-match ext] vector on a hit, nil on no match."
  core/file-extension)

;; EDN / structured content
(def ^{:stratum 0} edn-code-block
  "java.util.regex.Pattern matching a clojure/edn fenced code block
   (lang tag clojure, edn, or absent). Capture group 1 holds the block
   body. Use with re-find/re-seq: returns [whole-match body] vectors,
   nil/empty on no match."
  core/edn-code-block)

(def ^{:stratum 0} inline-already-implemented
  "java.util.regex.Pattern matching an inline
   `{:status :already-implemented ...}` EDN map within free text. No capture
   groups. Use with re-find: returns the matched substring on a hit, nil on
   no match."
  core/inline-already-implemented)

;; Rate-limit detection
(def ^{:stratum 0} rate-limit
  "Canonical java.util.regex.Pattern (case-insensitive) detecting
   rate-limit / quota messages in LLM or API responses (e.g. \"rate limit\",
   \"429\", \"quota exceeded\", \"resets 3pm\"). Use everywhere instead of
   ad-hoc regexes. Use with re-find: returns the matched substring on a hit,
   nil on no match."
  core/rate-limit)
