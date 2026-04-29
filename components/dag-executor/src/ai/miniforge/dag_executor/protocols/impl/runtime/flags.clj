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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.flags
  "Flag-dialect lookup for the OCI-CLI executor.

   Where Docker and Podman differ at the CLI surface, the executor consults
   this map rather than branching on `:runtime/kind` inline. Phase 1 ships
   only Docker entries; Phase 2 adds Podman entries side-by-side.

   Per `feedback`-style guidance from the spec: code SHALL prefer capability
   checks over kind branches; explicit kind entries here are documentation
   for the dialect difference being papered over.")

;; ============================================================================
;; Per-runtime dialect
;; ============================================================================

(def docker-dialect
  "Docker CLI flag dialect.

   Keys:
     :info-format-template — Go template passed to `<exe> info --format`
                             to extract the server version.
     :tmpfs-mount-options  — comma-separated options string appended to a
                             `--tmpfs <path>:<options>` argument. Includes
                             `uid=` / `gid=` because Docker supports them
                             when running with a numeric --user."
  {:info-format-template "{{.ServerVersion}}"
   :tmpfs-mount-options  "rw,nosuid,nodev,exec,size=512m,uid=1000,gid=1000"})

(def dialects
  "Map of runtime-kind -> dialect map. Phase 2 adds :podman."
  {:docker docker-dialect})

;; ============================================================================
;; Lookup
;; ============================================================================

(defn dialect-for
  "Return the dialect map for a runtime kind, or the Docker dialect as a
   fallback. The fallback is intentional: Phase 1 only knows Docker, and any
   non-Docker descriptor would have been rejected at construction time. The
   fallback is here so a future runtime that has not yet declared dialect
   overrides still produces sensible defaults."
  [kind]
  (get dialects kind docker-dialect))

(defn flag
  "Look up a single dialect entry for a runtime kind."
  [kind k]
  (get (dialect-for kind) k))
