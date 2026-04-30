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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.boundary-exemption-test
  "Boundary-namespace exemption: throws in cli/web/http/mcp/etc. are skipped."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(deftest cli-namespace-is-boundary
  (testing "*.cli.* and *-main namespaces are boundaries"
    (is (true?  (exc/boundary-namespace? 'ai.miniforge.cli.main)))
    (is (true?  (exc/boundary-namespace? 'ai.miniforge.cli.commands.scan)))
    (is (true?  (exc/boundary-namespace? 'ai.miniforge.foo.bar-main)))))

(deftest http-and-web-namespaces-are-boundaries
  (testing "explicit `http` and `web` segments are boundaries"
    (is (true? (exc/boundary-namespace? 'ai.miniforge.foo.http.handlers)))
    (is (true? (exc/boundary-namespace? 'ai.miniforge.web.handlers))))

  (testing "component names that merely contain `http` are NOT boundaries"
    ;; bb-data-plane-http is a component name, not a `.http.` boundary
    ;; — its core/impl namespaces still need to return anomalies internally.
    (is (false? (exc/boundary-namespace? 'ai.miniforge.bb-data-plane-http.core)))))

(deftest mcp-listener-consumer-and-boundary-namespaces
  (testing "the explicit boundary segments are detected"
    (is (true? (exc/boundary-namespace? 'ai.miniforge.mcp.bridge)))
    (is (true? (exc/boundary-namespace? 'ai.miniforge.kafka.consumer)))
    (is (true? (exc/boundary-namespace? 'ai.miniforge.events.listener)))
    (is (true? (exc/boundary-namespace? 'ai.miniforge.foo.boundary.in)))))

(deftest core-namespaces-are-not-boundaries
  (testing "ordinary component core namespaces are not boundaries"
    (is (false? (exc/boundary-namespace? 'ai.miniforge.agent.planner)))
    (is (false? (exc/boundary-namespace? 'ai.miniforge.workflow.runner)))
    (is (false? (exc/boundary-namespace? 'ai.miniforge.config.user)))))

(deftest boundary-files-yield-zero-violations
  (testing "throws inside a CLI namespace produce no violations"
    (let [src "(ns ai.miniforge.cli.main)
              (defn -main [& _]
                (throw (ex-info \"bad cli\" {})))"
          {:keys [boundary? violations]} (exc/analyze-content "main.clj" src)]
      (is (true? boundary?))
      (is (zero? (count violations)))))

  (testing "throws inside an HTTP boundary namespace produce no violations"
    (let [src "(ns ai.miniforge.foo.http.routes)
              (defn boom []
                (throw (ex-info \"500\" {:status 500})))"
          {:keys [boundary? violations]} (exc/analyze-content "routes.clj" src)]
      (is (true? boundary?))
      (is (zero? (count violations))))))
