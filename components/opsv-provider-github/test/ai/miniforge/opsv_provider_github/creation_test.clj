;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.creation-test
  (:require [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.opsv-provider-github.fixtures :as f]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} exact-payload-is-sent-after-head-confirmation-test
  (let [calls (atom [])
        runtime (f/runtime calls [(f/head-response) (f/response (f/pull-request))])
        result (provider/create-pr! runtime (f/transaction :committing) f/payload)
        [read-call post-call] @calls
        body (json/parse-string (get-in post-call [:options :in]) true)]
    (is (= :succeeded (:effect/outcome result)))
    (is (= 17 (get-in result [:effect/observed :pr/number])))
    (is (= "repos/example/opsv/git/ref/heads/opsv%2Fscaling"
           (get-in read-call [:arguments 2])))
    (is (= "GET" (get-in read-call [:arguments 6])))
    (is (= "POST" (get-in post-call [:arguments 6])))
    (is (= ["--input" "-"] (take-last 2 (:arguments post-call))))
    (is (= {:title (:pr/title f/payload) :body (:pr/body f/payload)
            :head (:pr/branch f/payload) :base (:pr/base f/payload) :draft true} body))))

(deftest ^{:stratum 0} runtime-dispatch-can-refuse-after-preflight-without-post-test
  (let [calls (atom [])
        runtime (f/runtime calls [(f/head-response)])
        refused {:effect/outcome :failed :effect/failure "runtime refused"}
        result (provider/create-pr! runtime (f/transaction :committing) f/payload (constantly refused))]
    (is (= refused result))
    (is (= ["GET"] (mapv #(get-in % [:arguments 6]) @calls)))
    (is (= :invalid-input
           (:anomaly/type (provider/create-pr! runtime (f/transaction :committing) f/payload nil))))))

(comment
  (f/pull-request))
