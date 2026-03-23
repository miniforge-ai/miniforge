(ns ai.miniforge.patterns.interface
  "Centralized, named regex patterns for reuse across components.

   Instead of inline regex literals, require this namespace and
   reference patterns by name:

     (re-find patterns/md-heading-file-path line)
     (re-find patterns/rate-limit response-text)"
  (:require [ai.miniforge.patterns.core :as core]))

;; Markdown / file-path extraction
(def md-heading-file-path    core/md-heading-file-path)
(def md-delimited-file-path  core/md-delimited-file-path)
(def md-label-file-path      core/md-label-file-path)
(def md-code-block           core/md-code-block)

;; File extensions
(def file-extension          core/file-extension)

;; EDN / structured content
(def edn-code-block          core/edn-code-block)
(def inline-already-implemented core/inline-already-implemented)

;; Rate-limit detection
(def rate-limit              core/rate-limit)
