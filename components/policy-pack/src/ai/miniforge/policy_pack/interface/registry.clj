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

(def PolicyPackRegistry registry/PolicyPackRegistry)
(def create-registry registry/create-registry)
(def register-pack registry/register-pack)
(def get-pack registry/get-pack)
(def get-pack-version registry/get-pack-version)
(def list-packs registry/list-packs)
(def delete-pack registry/delete-pack)
(def resolve-pack registry/resolve-pack)
(def get-rules-for-context registry/get-rules-for-context)
(def glob-matches? registry/glob-matches?)
(def compare-versions registry/compare-versions)
