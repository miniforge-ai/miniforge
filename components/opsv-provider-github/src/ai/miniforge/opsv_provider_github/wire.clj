;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.wire
  "Pure GitHub projection and exact provider observation matching."
  (:require [clojure.string :as str])
  (:import [java.net URLEncoder]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} payload-keys [:pr/repo :pr/base :pr/branch :pr/head-sha :pr/title :pr/body :pr/draft?])

(def ^{:stratum 0} provider-states #{"open" "closed"})

(defn- ^{:stratum 0} segment [value]
  (str/replace (URLEncoder/encode value "UTF-8") "+" "%20"))

(defn ^{:stratum 0} create-body [payload]
  {:title (:pr/title payload)
   :body (:pr/body payload)
   :base (:pr/base payload)
   :head (:pr/branch payload)
   :draft (:pr/draft? payload)})

(defn ^{:stratum 0} repository-path [payload]
  (str "repos/" (:pr/repo payload)))

(defn- ^{:stratum 0} same-repo? [expected actual]
  (and (string? actual) (= (str/lower-case expected) (str/lower-case actual))))

(defn ^{:stratum 0} observation [observed]
  {:pr/number (:number observed)
   :pr/url (:html_url observed)
   :pr/state (:state observed)
   :pr/head-sha (get-in observed [:head :sha])})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matching-pr? [payload observed]
  (and (pos-int? (:number observed))
       (string? (:html_url observed))
       (str/starts-with? (:html_url observed) "https://")
       (contains? provider-states (:state observed))
       (same-repo? (:pr/repo payload) (get-in observed [:base :repo :full_name]))
       (same-repo? (:pr/repo payload) (get-in observed [:head :repo :full_name]))
       (= (:pr/base payload) (get-in observed [:base :ref]))
       (= (:pr/branch payload) (get-in observed [:head :ref]))
       (= (:pr/head-sha payload) (get-in observed [:head :sha]))
       (= (:pr/title payload) (:title observed))
       (= (:pr/body payload) (:body observed))
       (= (:pr/draft? payload) (:draft observed))))

(defn ^{:stratum 1} head-path [payload]
  (str (repository-path payload) "/git/ref/heads/" (segment (:pr/branch payload))))

(defn ^{:stratum 1} pulls-path [payload]
  (str (repository-path payload) "/pulls"))

(defn ^{:stratum 1} listing-path [payload]
  (let [owner (first (str/split (:pr/repo payload) #"/"))
        head (segment (str owner ":" (:pr/branch payload)))
        base (segment (:pr/base payload))]
    (str (repository-path payload) "/pulls?state=all&per_page=100&head=" head "&base=" base)))

(comment
  (create-body {}))
