;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.timeline-values
  "Timeline field extraction and formatting; no event dispatch or stream traversal."
  (:require [ai.miniforge.event-stream.messages :as messages]
            [slingshot.slingshot :refer [try+]])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const args-preview-length 60)

;; Time formatting helpers
(defn- ^{:stratum 0} make-hms-formatter
  "Create a thread-local HH:mm:ss formatter in UTC."
  ^SimpleDateFormat []
  (doto (SimpleDateFormat. "HH:mm:ss")
    (.setTimeZone (TimeZone/getTimeZone "UTC"))))

(defn ^{:stratum 0} ts->epoch-ms
  "Coerce `ts` to epoch-ms long. Returns nil on failure."
  [ts]
  (try+
    (cond
      (nil? ts)            nil
      (instance? Date ts)  (.getTime ^Date ts)
      (number? ts)         (long ts)
      (string? ts)         (-> (java.time.Instant/parse ts)
                               (.toEpochMilli))
      :else                nil)
    (catch Exception _
      nil)))

;; Field extraction helpers
(defn ^{:stratum 0} event-timestamp [event]
  (:event/timestamp event))

(defn ^{:stratum 0} event-phase [event]
  (or (:workflow/phase event) (messages/t :timeline/no-phase)))

(defn ^{:stratum 0} event-type [event]
  (:event/type event))

(defn ^{:stratum 0} event-message
  "Return the renderable event message, or an empty string when absent or malformed."
  [event]
  (let [message (get event :message)]
    (if (string? message) message "")))

(defn- ^{:stratum 0} truncate-with-suffix
  [s n]
  (let [suffix        (str (messages/t :timeline/truncation-suffix))
        suffix-length (min (count suffix) (max 0 n))
        prefix-length (max 0 (- n suffix-length))]
    (str (subs s 0 prefix-length)
         (subs suffix 0 suffix-length))))

;; Duration helpers
(defn ^{:stratum 0} format-duration-ms
  "Format `ms` as a human-readable duration string. Both shapes
   (mins+secs, secs-only) flow through the user catalog."
  [ms]
  (let [total-s (long (/ ms 1000))
        m       (long (/ total-s 60))
        s       (long (rem total-s 60))]
    (if (pos? m)
      (messages/t :timeline/duration-mins-secs {:mins m :secs s})
      (messages/t :timeline/duration-secs      {:secs s}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} truncate
  "Truncate string `s` to at most `n` characters, appending the
   localized `:timeline/truncation-suffix` if cut."
  [s n]
  (cond
    (not (string? s)) nil
    (<= (count s) n) s
    :else (truncate-with-suffix s n)))

(def ^{:stratum 1} ^:private ^ThreadLocal hms-formatter-local
  "Thread-local SimpleDateFormat to avoid allocation on hot paths."
  (proxy [ThreadLocal] []
    (initialValue [] (make-hms-formatter))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} args-summary
  "Extract the args preview from an event, truncated to `args-preview-length` chars.
   Prefers `:tool/args-digest :digest/preview`, falls back to `:message`."
  [event]
  (let [preview (get-in event [:tool/args-digest :digest/preview])
        raw     (if (string? preview) preview (event-message event))]
    (truncate raw args-preview-length)))

(defn ^{:stratum 2} format-hms
  "Format `ts` (a Date, long epoch-ms, or ISO-8601 string) as HH:mm:ss.
   Returns the localized `:timeline/unknown-time` sentinel when `ts` is
   nil or unparseable."
  [ts]
  (try+
    (if-some [epoch-ms (ts->epoch-ms ts)]
      (.format ^SimpleDateFormat (.get hms-formatter-local) (Date. epoch-ms))
      (messages/t :timeline/unknown-time))
    (catch Exception _
      (messages/t :timeline/unknown-time))))

(comment
  (format-hms nil))
