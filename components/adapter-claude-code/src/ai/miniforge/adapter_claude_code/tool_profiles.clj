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

(ns ai.miniforge.adapter-claude-code.tool-profiles
  "Tool profile contributions for the Claude CLI native toolset.

   Profile data lives in
   resources/config/adapter_claude_code/tool-profiles.edn — this
   namespace loads it and contributes the entries to
   progress-detector's default tool registry at namespace load.

   Architectural intent: progress-detector ships zero hardcoded
   tool data — components that vendor a tool contribute their own
   profile. This namespace is the contribution from the Claude CLI
   adapter.

   Registration is via a defonce side-effect at namespace load.
   `register-profiles!` is itself idempotent (each entry overwrites
   by :tool/id) so re-invocation in tests or after reload is safe."
  (:require
   [ai.miniforge.progress-detector.interface :as pd]
   [clojure.edn                              :as edn]
   [clojure.java.io                          :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Profile data loader

(def ^:private profiles-resource-path
  "Classpath path to the EDN holding the eight Claude CLI tool
   profiles. Pulled to a constant so tests and REPL exploration
   reference the same string."
  "config/adapter_claude_code/tool-profiles.edn")

(def ^:private profiles-section-key
  "Top-level EDN key the catalog is filed under."
  :adapter-claude-code/tool-profiles)

(defn- load-profiles
  "Read the tool-profiles EDN catalog from the classpath and return
   the profile vector. Returns nil if the resource is missing — that
   is a packaging error, not a runtime condition the caller should
   handle."
  []
  (when-let [res (io/resource profiles-resource-path)]
    (-> res slurp edn/read-string (get profiles-section-key))))

(def claude-cli-profiles
  "Loaded profile entries for the eight Claude CLI native tools.
   Resolved at namespace load from
   resources/config/adapter_claude_code/tool-profiles.edn — the
   data lives in EDN, not in this code, so changes to determinism
   or category sets don't require a code change here."
  (load-profiles))

;------------------------------------------------------------------------------ Layer 1
;; Registration

(defn register-profiles!
  "Register every profile in `claude-cli-profiles` against `registry`.

   Idempotent: each entry overwrites by :tool/id, so calling twice
   leaves the registry shape unchanged.

   Arguments:
     registry - registry atom (defaults to pd/default-tool-registry)

   Returns: the final registry map."
  ([]
   (register-profiles! pd/default-tool-registry))
  ([registry]
   (last (mapv #(pd/register-tool-profile! registry %) claude-cli-profiles))))

;------------------------------------------------------------------------------ Layer 2
;; Load-time contribution

(defonce ^:private registered?
  ;; Side-effect at namespace load: contribute the eight profiles to
  ;; the process-wide registry. defonce makes it a one-shot;
  ;; register-profiles! itself is idempotent so re-invocation in
  ;; tests is safe.
  (do (register-profiles!) true))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Inspect the registered profiles
  (require '[ai.miniforge.progress-detector.interface :as pd])
  (pd/all-tool-ids)
  ;; => [:tool/Bash :tool/Edit :tool/Glob :tool/Grep :tool/Read
  ;;     :tool/WebFetch :tool/WebSearch :tool/Write]

  (pd/tool-determinism :tool/Read)        ; => :stable-with-resource-version
  (pd/tool-determinism :tool/Bash)        ; => :environment-dependent
  (pd/tool-determinism :tool/UnknownTool) ; => :unstable (registry default)

  ;; Re-registering is idempotent
  (let [reg (pd/make-tool-registry)]
    (register-profiles! reg)
    (register-profiles! reg)
    (count @reg))
  ;; => 8

  :leave-this-here)
