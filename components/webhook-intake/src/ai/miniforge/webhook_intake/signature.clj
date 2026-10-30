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
(ns ai.miniforge.webhook-intake.signature
  "GitHub webhook signatures. GitHub signs each delivery's raw body with
   HMAC-SHA256 keyed by the webhook secret and sends
   `X-Hub-Signature-256: sha256=<hex digest>`."
  (:require
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} signature-prefix
  "Prefix of the X-Hub-Signature-256 value, naming the digest."
  "sha256=")

(def ^{:stratum 0} hmac-algorithm
  "JCA name of the MAC GitHub uses for X-Hub-Signature-256."
  "HmacSHA256")

(defn ^{:stratum 0} hex
  "Lower-case hex string of `bs`."
  [^bytes bs]
  (str/join (map #(format "%02x" (bit-and % 0xff)) bs)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sign
  "The X-Hub-Signature-256 value for `body` (bytes) under `secret`."
  [secret ^bytes body]
  (let [mac (doto (Mac/getInstance hmac-algorithm)
              (.init (SecretKeySpec. (.getBytes (str secret) "UTF-8") hmac-algorithm)))]
    (str signature-prefix (hex (.doFinal mac body)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid-signature?
  "True when `header` is the signature of `body` (bytes) under `secret`,
   compared in constant time. False for a blank secret or a missing
   header, so an unconfigured secret never accepts a delivery."
  [secret ^bytes body header]
  (boolean
   (and (not (str/blank? secret))
        (string? header)
        (MessageDigest/isEqual (.getBytes ^String (sign secret body) "UTF-8")
                               (.getBytes ^String header "UTF-8")))))
