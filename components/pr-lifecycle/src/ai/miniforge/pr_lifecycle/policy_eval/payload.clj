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

(ns ai.miniforge.pr-lifecycle.policy-eval.payload
  "N13 §2.5 Comment Response Agent — Layer 0: Foundations.

   Identify which comments came from the Standards Reviewer and
   parse out their embedded EDN payload. No I/O, no business logic
   — just the cheap predicate + a delegate to the round-trip
   reader in `compliance-scanner.interface`.

   Sibling files:
     - plan.clj   — Layer 1 (per-comment classify + plan)
     - fs.clj     — Layer 2 (file I/O)
     - git.clj    — Layer 3a (commit + push)
     - reply.clj  — Layer 3b (reply + resolve)

   Consumed by `pr-lifecycle.policy-eval-responder` (orchestrator)."
  (:require
   [ai.miniforge.compliance-scanner.interface :as scanner]))

(def policy-eval-author
  "GitHub login the renderer posts under (per
   `compliance-scanner.comments/violation->comment`)."
  "miniforge-policy-evaluator[bot]")

(defn policy-eval-comment?
  "True when `comment` was posted by the N13 Standards Reviewer
   (matched by author login). Comments from other bots / humans are
   handled by the general LLM responder, not by this module."
  [comment]
  (= policy-eval-author (:comment/author comment)))

(defn extract-policy-payload
  "Return the embedded `:comment/payload` map from `comment`'s body,
   or nil. Delegates to `compliance-scanner.interface/extract-comment-payload`
   so the round-trip is the inverse of the renderer."
  [comment]
  (scanner/extract-comment-payload (:comment/body comment)))
