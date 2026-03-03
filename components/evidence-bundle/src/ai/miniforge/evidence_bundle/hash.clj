(ns ai.miniforge.evidence-bundle.hash
  "Content hashing utilities for evidence artifacts.
   Provides SHA-256 digest computation for tamper-evident audit trails.")

;------------------------------------------------------------------------------ Layer 0
;; Content Hashing

(defn content-hash
  "Compute SHA-256 digest of artifact content.
   Returns hex-encoded hash string."
  [content]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (pr-str content) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;------------------------------------------------------------------------------ Layer 1
;; Content Hash Verification

(defn verify-content-hash
  "Verify that content matches its stored hash.

   Arguments:
   - content: The data to verify (will be pr-str'd and hashed)
   - expected-hash: The hex-encoded SHA-256 hash to compare against

   Returns {:valid? bool :computed-hash string :expected-hash string}"
  [content expected-hash]
  (let [computed (content-hash content)]
    {:valid? (= computed expected-hash)
     :computed-hash computed
     :expected-hash expected-hash}))

(defn verify-evidence-bundle
  "Verify the integrity of an evidence bundle by checking its content hash.

   Recomputes the hash of the bundle (excluding the hash field itself) and
   compares against the stored hash.

   Returns {:valid? bool :computed-hash string :expected-hash string}"
  [bundle]
  (let [expected (get bundle :evidence/content-hash)
        ;; Hash the bundle without the hash field itself
        content (dissoc bundle :evidence/content-hash)]
    (if expected
      (verify-content-hash content expected)
      {:valid? false
       :computed-hash nil
       :expected-hash nil
       :error "No content hash present in bundle"})))
