;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.descriptor-receipt
  "Decode only the bounded native descriptor protocol, never pathname attributes."
  (:require [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io InputStream]
           [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} receipt-pattern
  "Nonnegative device/inode fields and one newline; out-of-host-range values refuse."
  #"([0-9]{1,20}) ([0-9]{1,20})\n")

(def ^{:stratum 0 :private true} receipt-limit
  "Twenty digits per identity field, one separator and one terminating newline."
  42)

(defn- ^{:stratum 0} identity-value [device inode]
  (try+
    {:storage/device-number (Long/parseLong device)
     :storage/inode (Long/parseLong inode)}
    (catch NumberFormatException _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode [text]
  (when-let [[_ device inode] (re-matches receipt-pattern text)]
    (identity-value device inode)))

(defn ^{:stratum 1} read! [^InputStream input]
  (with-open [stream input]
    (String. (.readNBytes stream (inc receipt-limit)) StandardCharsets/US_ASCII)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} receive! [until ^Process child]
  (let [captured (future-call (partial read! (.getInputStream child)))]
    (try+
      (some-> (deref captured (deadline/remaining-ms until) nil) decode)
      (finally (future-cancel captured)))))

(comment (decode "1 2\n"))
