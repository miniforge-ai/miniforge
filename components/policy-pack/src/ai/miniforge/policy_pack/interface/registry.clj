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

(ns ai.miniforge.policy-pack.interface.registry
  "Registry-facing policy-pack API."
  (:require
   [ai.miniforge.policy-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Registry protocol and helpers

(def PolicyPackRegistry
  "Protocol for managing policy packs: CRUD, import/export, validation, and
   composition. Implemented by the in-memory registry from create-registry."
  registry/PolicyPackRegistry)

(def create-registry
  "Create an in-memory PolicyPackRegistry. Arity [] or [opts] (opts may carry
   :logger). Returns a registry value backed by an atom."
  registry/create-registry)

(def register-pack
  "Protocol fn. (register-pack registry pack) registers a pack (or new
   version), keyed by :pack/id and :pack/version. Returns the registered pack;
   throws an :anomalies/incorrect anomaly when the pack fails schema validation."
  registry/register-pack)

(def get-pack
  "Protocol fn. (get-pack registry pack-id) returns the latest-version pack for
   pack-id, or nil if none is registered."
  registry/get-pack)

(def get-pack-version
  "Protocol fn. (get-pack-version registry pack-id version) returns the pack at
   the exact version, or nil if not found."
  registry/get-pack-version)

(def list-packs
  "Protocol fn. (list-packs registry criteria) returns a vector of pack-summary
   maps (:pack/id :pack/name :pack/version :pack/author :pack/description
   :rule-count). Criteria may filter by :category, :author, :search."
  registry/list-packs)

(def delete-pack
  "Protocol fn. (delete-pack registry pack-id version) removes the pack version.
   Returns true if deleted, false if it was not present."
  registry/delete-pack)

(def resolve-pack
  "Protocol fn. (resolve-pack registry pack-id) returns the pack with all
   :pack/extends parents recursively merged (child rules override parents by
   :rule/id), or nil if the pack is not found."
  registry/resolve-pack)

(def get-rules-for-context
  "Protocol fn. (get-rules-for-context registry pack-ids context) resolves the
   named packs, filters their rules by the context (:task, :artifact, :repo,
   :phase), deduplicates by :rule/id, and returns a vector of applicable rules."
  registry/get-rules-for-context)

(def glob-matches?
  "Predicate. (glob-matches? pattern path) returns true when path matches the
   glob pattern (supports * within a segment and ** across segments); false on
   no match or a bad pattern."
  registry/glob-matches?)

(def compare-versions
  "Compare two DateVer (YYYY.MM.DD) version strings. Returns a negative int if
   a < b, 0 if equal, positive if a > b; unparseable versions compare as
   [0 0 0]."
  registry/compare-versions)
