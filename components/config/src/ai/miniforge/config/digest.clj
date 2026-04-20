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

(ns ai.miniforge.config.digest
  "Content integrity verification for governance config files.

   Computes SHA-256 digests of governance EDN files and verifies them
   against a manifest to detect tampering.

   Layer 0: SHA-256 computation
   Layer 1: Manifest loading and verification"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   [java.security MessageDigest]))

;------------------------------------------------------------------------------ Layer 0
;; SHA-256 computation

(defn bytes->hex
  "Convert byte array to lowercase hex string. Babashka/GraalVM compatible."
  [^bytes ba]
  (let [sb (StringBuilder. (* 2 (alength ba)))]
    (dotimes [i (alength ba)]
      (.append sb (format "%02x" (aget ba i))))
    (.toString sb)))

(defn sha256-hex
  "Compute SHA-256 hex digest of a string.
   Returns a prefixed string like \"sha256:a1b2c3d4...\"."
  [^String content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes content "UTF-8"))]
    (str "sha256:" (bytes->hex hash-bytes))))

;------------------------------------------------------------------------------ Layer 1
;; Manifest loading and verification

(defn load-digest-manifest
  "Load governance/digests.edn from classpath.
   Returns a map of config-key -> digest-string, or nil if not found."
  []
  (when-let [resource (io/resource "config/governance/digests.edn")]
    (try
      (edn/read-string (slurp resource))
      (catch Exception _e
        nil))))

(defn verify-governance-file
  "Verify content against the digest manifest.

   Arguments:
   - config-key - Keyword like :readiness, :risk, etc.
   - content    - The raw string content of the governance file.

   Returns:
   - :ok            - Digest matches
   - :no-manifest   - No digests.edn found (skip verification)
   - :no-entry      - No entry for this config-key in manifest
   - :mismatch      - Digest does not match (possible tampering)

   For :knowledge-safety, a :mismatch is a hard failure.
   For other configs, :mismatch is a warning."
  [config-key content]
  (let [manifest (load-digest-manifest)
        expected (when manifest (get manifest config-key))
        actual   (when expected (sha256-hex content))]
    (cond
      (nil? manifest)     :no-manifest
      (nil? expected)     :no-entry
      (= expected actual) :ok
      :else               :mismatch)))
