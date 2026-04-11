// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Integration tests: deserialize JSON fixtures that mirror the Clojure event
//! stream format, then verify fields and re-serialization roundtrip.

use miniforge_contracts::*;
use serde_json::json;

/// Helper — load a fixture file from tests/fixtures/.
fn load_fixture(name: &str) -> serde_json::Value {
    let path = format!("{}/tests/fixtures/{}", env!("CARGO_MANIFEST_DIR"), name);
    let contents = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("Failed to read fixture {}: {}", path, e));
    serde_json::from_str(&contents)
        .unwrap_or_else(|e| panic!("Failed to parse fixture {}: {}", name, e))
}

// ============================================================================
// WorkflowRun fixture tests
// ============================================================================

#[test]
fn fixture_workflow_run_deserializes() {
    let v = load_fixture("workflow_run.json");
    let wf: WorkflowRun = serde_json::from_value(v).unwrap();

    assert_eq!(wf.key, "feature-auth-oauth2");
    assert_eq!(wf.status, WorkflowStatus::Running);
    assert_eq!(wf.phase, WorkflowPhase::Implement);
    assert_eq!(
        wf.id.to_string(),
        "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
    );

    // Extra fields from Clojure event stream pass through
    assert_eq!(
        wf.extra.get("workflow/intent"),
        Some(&json!("Implement OAuth2 authentication flow"))
    );
    assert_eq!(
        wf.extra.get("workflow/trigger-source"),
        Some(&json!("cli"))
    );
}

#[test]
fn fixture_workflow_run_roundtrip_preserves_extras() {
    let v = load_fixture("workflow_run.json");
    let wf: WorkflowRun = serde_json::from_value(v.clone()).unwrap();
    let reserialized = serde_json::to_value(&wf).unwrap();

    // All original keys should be present in the re-serialized output
    let orig = v.as_object().unwrap();
    let reser = reserialized.as_object().unwrap();
    for key in orig.keys() {
        assert!(
            reser.contains_key(key),
            "Missing key after roundtrip: {}",
            key
        );
    }
}

// ============================================================================
// PolicyEvaluation fixture tests
// ============================================================================

#[test]
fn fixture_policy_evaluation_pass() {
    let v = load_fixture("policy_evaluation.json");
    let pe: PolicyEvaluation = serde_json::from_value(v).unwrap();

    assert_eq!(pe.pr_id, "miniforge/ai#42");
    assert_eq!(pe.result, EvalResult::Pass);
    assert_eq!(
        pe.rules_applied,
        vec!["no-force-push", "require-tests", "require-review"]
    );

    // Extra normative fields
    assert_eq!(pe.extra.get("policy-eval/passed?"), Some(&json!(true)));
}

#[test]
fn fixture_policy_evaluation_fail_with_violations() {
    let v = load_fixture("policy_evaluation_fail.json");
    let pe: PolicyEvaluation = serde_json::from_value(v).unwrap();

    assert_eq!(pe.result, EvalResult::Fail);
    assert_eq!(pe.extra.get("policy-eval/passed?"), Some(&json!(false)));

    // Violations array passes through as extra JSON
    let violations = pe.extra.get("policy-eval/violations").unwrap();
    assert!(violations.is_array());
    let arr = violations.as_array().unwrap();
    assert_eq!(arr.len(), 1);
    assert_eq!(arr[0]["violation/severity"], "high");
    assert_eq!(arr[0]["violation/category"], "testing");
}

// ============================================================================
// AttentionItem fixture tests
// ============================================================================

#[test]
fn fixture_attention_item_deserializes() {
    let v = load_fixture("attention_item.json");
    let ai: AttentionItem = serde_json::from_value(v).unwrap();

    assert_eq!(ai.severity, Severity::High);
    assert_eq!(
        ai.summary,
        "PR #43 has unresolved policy violations blocking merge"
    );
    assert_eq!(ai.source_type, AttentionSourceType::Policy);
    assert_eq!(ai.source_id, "c3d4e5f6-a7b8-4901-cdef-012345678901");

    // Normative extras
    assert_eq!(ai.extra.get("attention/resolved?"), Some(&json!(false)));
    assert_eq!(
        ai.extra.get("attention/acknowledged?"),
        Some(&json!(false))
    );
}

#[test]
fn fixture_attention_item_roundtrip() {
    let v = load_fixture("attention_item.json");
    let ai: AttentionItem = serde_json::from_value(v.clone()).unwrap();
    let reser = serde_json::to_value(&ai).unwrap();

    let orig = v.as_object().unwrap();
    let rt = reser.as_object().unwrap();
    for key in orig.keys() {
        assert!(rt.contains_key(key), "Missing key after roundtrip: {}", key);
    }
}

// ============================================================================
// AgentSession fixture tests
// ============================================================================

#[test]
fn fixture_agent_session_full() {
    let v = load_fixture("agent_session.json");
    let sess: AgentSession = serde_json::from_value(v).unwrap();

    assert_eq!(sess.vendor, "claude-code");
    assert_eq!(sess.name, "PR Review Agent");
    assert_eq!(sess.status, AgentStatus::Executing);
    assert_eq!(sess.external_id, "session-2026-04-10-alpha");
    assert_eq!(
        sess.capabilities,
        vec!["code", "test", "review"]
    );
    assert_eq!(sess.heartbeat_interval_ms, 30000);
    assert_eq!(
        sess.tags,
        vec!["pr-review", "team-alpha"]
    );
    assert_eq!(
        sess.task,
        Some("Reviewing PR #42 — checking test coverage".into())
    );

    // Metrics and decisions pass through as extra
    assert!(sess.extra.contains_key("agent/metrics"));
    assert!(sess.extra.contains_key("agent/decisions"));
}

#[test]
fn fixture_agent_session_minimal_defaults() {
    let v = load_fixture("agent_session_minimal.json");
    let sess: AgentSession = serde_json::from_value(v).unwrap();

    assert_eq!(sess.vendor, "claude-code");
    assert_eq!(sess.status, AgentStatus::Idle);
    assert_eq!(sess.heartbeat_interval_ms, 30000); // default
    assert!(sess.capabilities.is_empty());
    assert!(sess.tags.is_empty());
    assert!(sess.task.is_none());
}

// ============================================================================
// PrFleetEntry fixture tests
// ============================================================================

#[test]
fn fixture_pr_fleet_entry_full() {
    let v = load_fixture("pr_fleet_entry.json");
    let pr: PrFleetEntry = serde_json::from_value(v).unwrap();

    assert_eq!(pr.title, "Add OAuth2 authentication flow");
    assert_eq!(pr.repo, "miniforge/ai");
    assert_eq!(pr.number, Some(42));
    assert_eq!(pr.readiness, Some(0.85));
    assert_eq!(pr.risk, Some(PrRisk::Medium));
    assert_eq!(pr.status, Some("open".into()));
    assert_eq!(pr.ci_status, Some(CiStatus::Passed));
    assert_eq!(pr.depends_on, vec!["miniforge/ai#41"]);
}

#[test]
fn fixture_pr_fleet_entry_minimal() {
    let v = load_fixture("pr_fleet_entry_minimal.json");
    let pr: PrFleetEntry = serde_json::from_value(v).unwrap();

    assert_eq!(pr.title, "Fix typo in README");
    assert_eq!(pr.repo, "miniforge/docs");
    assert!(pr.number.is_none());
    assert!(pr.readiness.is_none());
    assert!(pr.risk.is_none());
    assert!(pr.ci_status.is_none());
    assert!(pr.depends_on.is_empty());
}

#[test]
fn fixture_pr_fleet_entry_roundtrip() {
    let v = load_fixture("pr_fleet_entry.json");
    let pr: PrFleetEntry = serde_json::from_value(v.clone()).unwrap();
    let reser = serde_json::to_value(&pr).unwrap();

    let orig = v.as_object().unwrap();
    let rt = reser.as_object().unwrap();
    for key in orig.keys() {
        assert!(rt.contains_key(key), "Missing key after roundtrip: {}", key);
    }
}

// ============================================================================
// Cross-entity: batch deserialization (simulating event stream)
// ============================================================================

#[test]
fn batch_event_stream_deserialization() {
    // Simulate a heterogeneous event stream where each event has a "type" tag
    let events = json!([
        {
            "event/type": "workflow-run",
            "workflow/id": "a1b2c3d4-e5f6-4789-abcd-ef0123456789",
            "workflow/key": "deploy-v2",
            "workflow/status": "completed",
            "workflow/phase": "release",
            "workflow/started-at": "2026-04-10T06:00:00Z"
        },
        {
            "event/type": "policy-evaluation",
            "eval/id": "b2c3d4e5-f6a7-4890-bcde-f01234567890",
            "eval/pr-id": "org/repo#99",
            "eval/result": "warn",
            "eval/rules-applied": ["lint-check"],
            "eval/evaluated-at": "2026-04-10T07:00:00Z"
        },
        {
            "event/type": "attention-item",
            "attention/id": "e5f6a7b8-c9d0-4123-efab-234567890123",
            "attention/severity": "critical",
            "attention/summary": "Agent exceeded budget",
            "attention/source-type": "agent",
            "attention/source-id": "agent-001"
        }
    ]);

    let arr = events.as_array().unwrap();

    // Dispatch based on event type
    for event in arr {
        let event_type = event["event/type"].as_str().unwrap();
        match event_type {
            "workflow-run" => {
                let wf: WorkflowRun = serde_json::from_value(event.clone()).unwrap();
                assert_eq!(wf.status, WorkflowStatus::Completed);
                assert_eq!(wf.phase, WorkflowPhase::Release);
            }
            "policy-evaluation" => {
                let pe: PolicyEvaluation = serde_json::from_value(event.clone()).unwrap();
                assert_eq!(pe.result, EvalResult::Warn);
            }
            "attention-item" => {
                let ai: AttentionItem = serde_json::from_value(event.clone()).unwrap();
                assert_eq!(ai.severity, Severity::Critical);
                assert_eq!(ai.source_type, AttentionSourceType::Agent);
            }
            _ => panic!("Unknown event type: {}", event_type),
        }
    }
}
