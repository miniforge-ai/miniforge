(ns ai.miniforge.patterns.core
  "Centralized regex pattern registry.

   Named patterns for reuse across components. Each pattern has a
   descriptive name so its purpose is clear at the call site.")

;;------------------------------------------------------------------------------ Layer 0
;; Markdown / file-path extraction patterns

(def md-heading-file-path
  "Match a file path in a markdown heading: ### path/to/file.clj"
  #"#{1,4}\s+[`*]*([^\s`*]+\.\w{1,6})[`*]*")

(def md-delimited-file-path
  "Match a file path in backticks or bold: **path/to/file.clj** or `path/to/file.clj`"
  #"[`*]+([^\s`*]+\.\w{1,6})[`*]+")

(def md-label-file-path
  "Match 'File: path/to/file.clj' or 'Path: path/to/file.clj'"
  #"(?i)(?:file|path):\s*`?([^\s`]+\.\w{1,6})`?")

(def md-code-block
  "Match a fenced code block: ```lang\\ncontent```"
  #"```(?:(\w+)\n)?([^`]+)```")

;;------------------------------------------------------------------------------ Layer 1
;; File extension patterns

(def file-extension
  "Extract file extension: .clj, .js, etc."
  #"\.(\w+)$")

;;------------------------------------------------------------------------------ Layer 2
;; EDN / structured content patterns

(def edn-code-block
  "Match a clojure/edn fenced code block."
  #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```")

(def inline-already-implemented
  "Match an inline {:status :already-implemented ...} EDN map."
  #"\{:status\s+:already-implemented[^}]*\}")

;;------------------------------------------------------------------------------ Layer 3
;; Rate-limit detection (canonical pattern — use this everywhere)

(def rate-limit
  "Detect rate-limit / quota messages in LLM or API responses."
  #"(?i)you've hit your limit|rate.?limit|429|quota.?exceeded|resets \d+[ap]m")
