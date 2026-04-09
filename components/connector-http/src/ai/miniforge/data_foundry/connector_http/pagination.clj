(ns ai.miniforge.data-foundry.connector-http.pagination
  "Pagination strategies for HTTP extraction."
  (:require [clojure.string :as str]))

(defn offset-params
  "Build query params for offset-based pagination."
  [{:keys [param page-size-param]} offset page-size]
  {(or param "offset") offset
   (or page-size-param "limit") page-size})

(defn cursor-params
  "Build query params for cursor-based pagination."
  [{:keys [param page-size-param]} cursor-value page-size]
  (cond-> {(or page-size-param "limit") page-size}
    cursor-value (assoc (or param "cursor") cursor-value)))

(defn next-offset
  "Calculate next offset for offset-based pagination."
  [current-offset records-count]
  (+ current-offset records-count))

(defn has-more-offset?
  "Determine if more pages exist for offset pagination."
  [response-body {:keys [total-path]} current-offset records-count]
  (if total-path
    (let [total (get-in response-body total-path)]
      (and (number? total) (< (+ current-offset records-count) total)))
    (pos? records-count)))

(defn extract-cursor-value
  "Extract next cursor value from response for cursor-based pagination."
  [response-body {:keys [next-cursor-path]}]
  (when next-cursor-path
    (get-in response-body next-cursor-path)))

;; --------------------------------------------------------------------------
;; Link-header pagination (RFC 5988)
;; --------------------------------------------------------------------------

(defn parse-link-header
  "Parse an HTTP Link header into a map of {rel url}.
   Example: '<https://api.github.com/repos?page=2>; rel=\"next\"'
   → {\"next\" \"https://api.github.com/repos?page=2\"}"
  [link-header]
  (when link-header
    (into {}
          (for [part  (str/split link-header #",\s*")
                :let  [[_ url] (re-find #"<([^>]+)>" part)
                       [_ rel] (re-find #"rel=\"([^\"]+)\"" part)]
                :when (and url rel)]
            [rel url]))))

(defn link-header-next-url
  "Extract the 'next' URL from a parsed Link header map."
  [links]
  (get links "next"))

(defn link-header-has-more?
  "Check if a Link header indicates more pages."
  [links]
  (boolean (link-header-next-url links)))
