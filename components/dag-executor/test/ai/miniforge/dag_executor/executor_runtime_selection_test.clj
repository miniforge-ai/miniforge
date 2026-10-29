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
(ns ai.miniforge.dag-executor.executor-runtime-selection-test
  "The registry's :docker slot runs on the container runtime that
   select-runtime picks (N11-delta §3), not a hard-coded Docker: an
   explicit :runtime-kind wins, otherwise Podman then Docker. Probes are
   stubbed through descriptor/runtime-info, so no runtime is needed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.executor :as sut]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stub-runtime-info
  "runtime-info stub keyed by runtime kind; absent kinds probe unavailable."
  [availability]
  (fn [d]
    (if (get availability (descriptor/kind d))
      {:available? true :runtime-version "stub"}
      {:available? false :reason "stubbed unavailable"})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} container-slot
  "The registry's :docker slot executor for `docker-config` on a host where
   `availability` says which runtimes answer, plus whether it is available."
  [availability docker-config]
  (with-redefs [descriptor/runtime-info (stub-runtime-info availability)]
    (let [executor (:docker (sut/create-executor-registry {:docker docker-config}))
          probe    (sut/available? executor)]
      {:kind       (sut/executor-type executor)
       :available? (and (result/ok? probe) (:available? (:data probe)))})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} podman-only-host-gets-a-podman-executor-test
  (testing "no explicit kind, only Podman answers: the slot runs on Podman"
    (is (= {:kind :podman :available? true}
           (container-slot {:podman true} {:image "img"})))))

(deftest ^{:stratum 2} auto-probe-order-test
  (testing "only Docker answers: Docker"
    (is (= {:kind :docker :available? true}
           (container-slot {:docker true} {:image "img"}))))
  (testing "both answer: Podman, first in the probe order"
    (is (= {:kind :podman :available? true}
           (container-slot {:podman true :docker true} {:image "img"})))))

(deftest ^{:stratum 2} explicit-kind-never-falls-back-test
  (testing "explicit :runtime-kind :docker on a Podman-only host: unavailable, never Podman"
    (is (= {:kind :unavailable :available? false}
           (container-slot {:podman true} {:image "img" :runtime-kind :docker})))))

(deftest ^{:stratum 2} no-runtime-yields-an-unavailable-slot-test
  (testing "nothing answers: the slot is unavailable, so select-executor skips it"
    (is (= {:kind :unavailable :available? false}
           (container-slot {} {:image "img"})))))

(deftest ^{:stratum 2} executable-without-kind-stays-rejected-test
  (testing "an :executable override without a kind is a selection error, even when the binary answers"
    (is (= {:kind :unavailable :available? false}
           (container-slot {:podman true :docker true}
                           {:image "img" :executable "/opt/custom/docker"}))))
  (testing "every operation returns the selection error"
    (with-redefs [descriptor/runtime-info (stub-runtime-info {:docker true})]
      (let [executor (:docker (sut/create-executor-registry
                               {:docker {:executable "/opt/custom/docker"}}))
            acquired (sut/acquire-environment! executor (random-uuid) {})]
        (is (result/err? acquired))
        (is (= :runtime/executable-requires-kind (get-in acquired [:error :code])))))))

(deftest ^{:stratum 2} legacy-docker-path-means-docker-test
  (testing ":docker-path names a Docker binary, so it selects Docker explicitly"
    (is (= {:kind :docker :available? true}
           (container-slot {:podman true :docker true}
                           {:image "img" :docker-path "/usr/local/bin/docker"})))))
