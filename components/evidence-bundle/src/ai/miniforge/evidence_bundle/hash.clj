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
