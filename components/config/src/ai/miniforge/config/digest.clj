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

(defn- bytes->hex
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
  (if-let [manifest (load-digest-manifest)]
    (if-let [expected (get manifest config-key)]
      (let [actual (sha256-hex content)]
        (if (= expected actual)
          :ok
          :mismatch))
      :no-entry)
    :no-manifest))
