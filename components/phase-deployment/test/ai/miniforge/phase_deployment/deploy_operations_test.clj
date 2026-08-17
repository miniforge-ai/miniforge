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
(ns ai.miniforge.phase-deployment.deploy-operations-test
  "The adapter exists for one property: what was dry-run is what gets
   applied. Re-rendering between the two would validate one artifact and
   ship another, which is the gap the governed seam exists to close."
  (:require
   [ai.miniforge.phase-deployment.deploy-operations :as operations]
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} config {:kustomize-dir "/k8s" :namespace "prod" :context "gke"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} apply-sends-exactly-the-dry-run-bytes-test
  ;; The render is deliberately made non-deterministic. If the adapter
  ;; re-rendered for the apply it would ship the second value, and this
  ;; asserts it ships the first.
  (let [renders (atom 0)
        applied (atom nil)]
    (with-redefs [provider/render! (fn [_] {:success? true :rendered-yaml (str "manifest-" (swap! renders inc))})
                  provider/dry-run! (fn [_ _] {:success? true})
                  provider/apply-rendered! (fn [_ text] (reset! applied text) {:success? true})]
      (let [ops (operations/operations)
            dry ((:dry-run! ops) config)]
        ((:apply! ops) config)
        (is (= "manifest-1" dry))
        (is (= "manifest-1" @applied)
            "the apply must send the bytes the dry-run validated")
        (is (= 1 @renders) "rendering twice would mean validating one thing and applying another")))))

(deftest ^{:stratum 1} a-rejected-dry-run-yields-no-manifest-test
  (testing "an API-server rejection denies rather than passing the render through"
    (with-redefs [provider/render! (fn [_] {:success? true :rendered-yaml "manifest"})
                  provider/dry-run! (fn [_ _] {:success? false :error "invalid"})]
      (is (nil? ((:dry-run! (operations/operations)) config)))))

  (testing "an apply with nothing rendered fails and never reaches the cluster"
    (let [reached (atom false)]
      (with-redefs [provider/apply-rendered! (fn [_ _] (reset! reached true) {:success? true})]
        (let [result ((:apply! (operations/operations)) config)]
          (is (:deploy/failed? result))
          (is (not @reached) "no manifest means no kubectl call"))))))
