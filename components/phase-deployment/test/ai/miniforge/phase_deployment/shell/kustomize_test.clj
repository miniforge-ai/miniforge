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
(ns ai.miniforge.phase-deployment.shell.kustomize-test
  (:require
   [ai.miniforge.phase-deployment.shell.exec :as exec]
   [ai.miniforge.phase-deployment.shell.kustomize :as kustomize]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} manifest-bytes "apiVersion: apps/v1\nkind: Deployment\n")

(defn ^{:stratum 0} blank-stderr-failure
  [message]
  (schema/failure :stdout message {:stderr ""}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} recording-shell
  [calls]
  (fn [command args & {:as options}]
    (swap! calls conj {:command command :args args :options options})
    (schema/success :stdout manifest-bytes)))

(deftest ^{:stratum 1} a-failed-build-retains-error-and-renders-no-manifest-test
  (let [result (with-redefs [exec/sh-with-timeout
                             (fn [& _] (blank-stderr-failure "build broke"))]
                 (kustomize/kustomize-render! "/deployment"))]
    (is (schema/failed? result))
    (is (nil? (:rendered-yaml result))
        "a nil manifest means no manifest, not a manifest under another key")
    (is (re-find #"build broke" (:error result)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} server-dry-run-and-apply-use-identical-input-test
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (recording-shell calls)]
      (kustomize/kubectl-apply! manifest-bytes
                                 :namespace "production"
                                 :context "cluster-1"
                                 :server-dry-run? true)
      (kustomize/kubectl-apply! manifest-bytes
                                 :namespace "production"
                                 :context "cluster-1"))
    (let [[dry-run apply-call] @calls]
      (is (= [manifest-bytes manifest-bytes]
             (mapv #(get-in % [:options :in]) @calls)))
      (is (= ["apply" "-f" "-" "--namespace" "production"
              "--context" "cluster-1" "--dry-run=server" "-o" "yaml"]
             (:args dry-run)))
      (testing "the real mutation cannot inherit the dry-run flag"
        (is (= ["apply" "-f" "-" "--namespace" "production"
                "--context" "cluster-1"]
               (:args apply-call)))))))

(deftest ^{:stratum 2} render-reports-the-manifest-under-a-stable-key-test
  ;; The trap this closes: a governed caller wired :render! and then read
  ;; :rendered-yaml off the result. Rendering used to return a raw shell
  ;; result, so that key was nil, and a healthy render read as an empty one
  ;; — which denies every deploy.
  (let [render-calls (atom [])
        rendered (with-redefs [exec/sh-with-timeout
                               (recording-shell render-calls)]
                   (kustomize/kustomize-render! "/deployment"))]
    (is (= manifest-bytes (:rendered-yaml rendered)))
    (testing "a render contacts no cluster"
      (is (= ["kustomize"] (mapv :command @render-calls))))))
