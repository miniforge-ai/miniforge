;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-schema
  (:require [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as native]
            [malli.util :as mu]
            [slingshot.slingshot :refer [try+]])
  (:import [java.net URI URISyntaxException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Image
  [:re #"\A(?:[a-z0-9-]+(?:\.[a-z0-9-]+)+|localhost)(?::[0-9]+)?/[a-z0-9]+(?:[._/-][a-z0-9]+)*@sha256:[0-9a-f]{64}\z"])

(defn- ^{:stratum 0} traffic-uri? [value]
  (try+
    (let [uri (URI. ^String value)]
      (and (contains? #{"http" "https"} (.getScheme uri))
           (seq (.getHost uri)) (nil? (.getRawUserInfo uri)) (nil? (.getRawFragment uri))))
    (catch URISyntaxException _ false)))

(defn- ^{:stratum 0} count-covers-workers? [value]
  (<= (:load/max-concurrency value) (:load/max-requests value)))

(defn- ^{:stratum 0} whole-seconds? [value] (zero? (mod value experiment/millis-per-second)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} LoadWorkerSettings
  [:map {:closed true}
   [:load/image Image]
   [:load/cpu-millis pos-int?]
   [:load/memory-mib pos-int?]])

(def ^{:stratum 1} Limits
  (-> experiment/Limits
      (mu/assoc :opsv/traffic-destinations [:vector {:min 1 :max 1} [:and :string [:fn traffic-uri?]]])
      (mu/assoc :opsv/duration-ms [:and [:int {:min experiment/millis-per-second :max experiment/maximum-duration-ms}]
                                  [:fn whole-seconds?]])
      (mu/assoc :opsv/load-limits [:and
                                  [:map {:closed true}
                                   [:load/max-requests pos-int?]
                                   [:load/max-concurrency pos-int?]
                                   [:load/max-rate-per-second pos-int?]]
                                  [:fn count-covers-workers?]])))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Input
  [:map {:closed true}
   [:declaration native/Declaration]
   [:request native/Request]
   [:comparison/artifact-ref :uuid]
   [:limits Limits]
   [:runtime LoadWorkerSettings]])

(comment ::one-http-destination-whole-second-pod-deadline-and-no-unbounded-client-mode)
