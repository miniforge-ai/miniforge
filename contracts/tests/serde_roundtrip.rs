// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! Integration tests: deserialize JSON fixtures → struct → re-serialize → compare.
//!
//! Each fixture file mirrors the shape of events from the Clojure event stream
//! (`~/.miniforge/events/`). Tests verify that Rust structs can faithfully
//! round-trip these events through serde_json.

use miniforge_contracts::*;

/// Load a fixture file relative to the tests/ directory.
fn fixture(name: &str) -> String {
    let path = format!(
        "{}/tests/fixtures/{name}",
        env!("CARGO_MANIFEST_DIR")
    );
    std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("Failed to read fixture {path}: {e}"))
}

/// Assert that deserializing JSON into T and re-serializing produces
/// a value-equivalent JSON document (ignoring key order).
fn assert_roundtrip<T>(json_str: &str)
where
    T: serde::de::DeserializeOwned + serde::Serialize + std::fmt::Debug,
{
    let parsed: T = serde_json::from_str(json_str)
        .unwrap_or_else(|e| panic!("Deserialize failed: {e}\nJSON:\n{json_str}"));

    let reserialized = serde_json::to_string(&parsed).unwrap();
    let reparsed: T = serde_json::from_str(&reserialized)
        .unwrap_or_else(|e| panic!("Re-deserialize failed: {e}\nJSON:\n{reserialized}"));

    // Compare through serde_json::Value to ignore key ordering
    let original_value: serde_json::Value = serde_json::from_str(json_str).unwrap();
    let roundtrip_value: serde_json::Value = serde_json::to_value(&reparsed).unwrap();
    assert_eq!(original_value, roundtrip_value,
        "Round-trip mismatch for {}\nOriginal: {original_value}\nRoundtrip: {roundtrip_value}",
        std::any::type_name::<T>());
}

// ---------------------------------------------------------------------------
// WorkflowRun fixtures
// ---------------------------------------------------------------------------

#[test]
fn test_workflow_run_full_fixture() {
    let json = fixture("workflow_run.json");
    assert_roundtrip::<WorkflowRun>(&json);
}

#[test]
fn test_workflow_run_minimal_fixture() {
    let json = fixture("workflow_run_minimal.json");
    assert_roundtrip::<WorkflowRun>(&json);
}

#[test]
fn test_workflow_run_field_values() {
    let json = fixture("workflow_run.json");
    let run: WorkflowRun = serde_json::from_str(&json).unwrap();

    assert_eq!(run.key, "feature-auth");
    assert_eq!(run.status, WorkflowStatus::Running);
    assert_eq!(run.phase, WorkflowPhase::Implement);
    assert_eq!(run.intent.as_deref(), Some("Implement OAuth2 login flow"));
    assert_eq!(run.trigger_source, Some(TriggerSource::Cli));
    assert!(run.correlation_id.is_some());
}

#[test]
fn test_workflow_run_all_statuses() {
    for status in &["pending", "running", "paused", "completed", "failed", "cancelled"] {
        let json = format!(r#""{status}""#);
        let parsed: WorkflowStatus = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse status {status}: {e}"));
        let reserialized = serde_json::to_string(&parsed).unwrap();
        assert_eq!(reserialized, json);
    }
}

#[test]
fn test_workflow_run_all_phases() {
    for phase in &["plan", "design", "implement", "verify", "review", "release", "observe"] {
        let json = format!(r#""{phase}""#);
        let parsed: WorkflowPhase = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse phase {phase}: {e}"));
        let reserialized = serde_json::to_string(&parsed).unwrap();
        assert_eq!(reserialized, json);
    }
}

// ---------------------------------------------------------------------------
// PolicyEvaluation fixtures
// ---------------------------------------------------------------------------

#[test]
fn test_policy_evaluation_fixture() {
    let json = fixture("policy_evaluation.json");
    assert_roundtrip::<PolicyEvaluation>(&json);
}

#[test]
fn test_policy_evaluation_with_violations_fixture() {
    let json = fixture("policy_evaluation_with_violations.json");
    assert_roundtrip::<PolicyEvaluation>(&json);
}

#[test]
fn test_policy_evaluation_field_values() {
    let json = fixture("policy_evaluation.json");
    let eval: PolicyEvaluation = serde_json::from_str(&json).unwrap();

    assert_eq!(eval.pr_id, "miniforge-ai/miniforge/123");
    assert_eq!(eval.result, EvalResult::Pass);
    assert_eq!(eval.rules_applied.len(), 3);
    assert!(eval.rules_applied.contains(&"no-force-push".to_string()));
    assert_eq!(eval.passed, Some(true));
    assert_eq!(eval.target_type, Some(EvalTargetType::Pr));
    assert_eq!(eval.packs_applied.as_ref().map(|v| v.len()), Some(2));
}

#[test]
fn test_policy_evaluation_violations_populated() {
    let json = fixture("policy_evaluation_with_violations.json");
    let eval: PolicyEvaluation = serde_json::from_str(&json).unwrap();

    assert_eq!(eval.result, EvalResult::Fail);
    assert_eq!(eval.passed, Some(false));

    let violations = eval.violations.expect("violations should be present");
    assert_eq!(violations.len(), 1);
    assert_eq!(violations[0].rule, "require-tests");
    assert_eq!(violations[0].category, ViolationCategory::Testing);
    assert_eq!(violations[0].severity, ViolationSeverity::High);
}

#[test]
fn test_eval_result_all_variants() {
    for variant in &["pass", "fail", "warn", "skip"] {
        let json = format!(r#""{variant}""#);
        let parsed: EvalResult = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse eval result {variant}: {e}"));
        let reserialized = serde_json::to_string(&parsed).unwrap();
        assert_eq!(reserialized, json);
    }
}

// ---------------------------------------------------------------------------
// AttentionItem fixtures
// ---------------------------------------------------------------------------

#[test]
fn test_attention_item_full_fixture() {
    let json = fixture("attention_item.json");
    assert_roundtrip::<AttentionItem>(&json);
}

#[test]
fn test_attention_item_minimal_fixture() {
    let json = fixture("attention_item_minimal.json");
    assert_roundtrip::<AttentionItem>(&json);
}

#[test]
fn test_attention_item_field_values() {
    let json = fixture("attention_item.json");
    let item: AttentionItem = serde_json::from_str(&json).unwrap();

    assert_eq!(item.severity, AttentionSeverity::High);
    assert!(item.summary.contains("Budget exceeded"));
    assert_eq!(item.source_type, AttentionSourceType::System);
    assert_eq!(item.source_id, "workflow-a1b2c3d4");
    assert_eq!(item.resolved, Some(false));
    assert_eq!(item.acknowledged, Some(false));
    assert!(item.derived_at.is_some());
    assert!(item.created_at.is_some());
}

#[test]
fn test_attention_item_minimal_optionals_absent() {
    let json = fixture("attention_item_minimal.json");
    let item: AttentionItem = serde_json::from_str(&json).unwrap();

    assert_eq!(item.severity, AttentionSeverity::Info);
    assert_eq!(item.source_type, AttentionSourceType::Agent);
    assert!(item.derived_at.is_none());
    assert!(item.created_at.is_none());
    assert!(item.resolved.is_none());
    assert!(item.acknowledged.is_none());
}

#[test]
fn test_attention_severity_all_variants() {
    for variant in &["info", "low", "medium", "high", "critical"] {
        let json = format!(r#""{variant}""#);
        let _: AttentionSeverity = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse severity {variant}: {e}"));
    }
}

#[test]
fn test_attention_source_type_all_variants() {
    for variant in &["workflow", "policy", "agent", "system", "human"] {
        let json = format!(r#""{variant}""#);
        let _: AttentionSourceType = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse source-type {variant}: {e}"));
    }
}

// ---------------------------------------------------------------------------
// AgentSession fixtures
// ---------------------------------------------------------------------------

#[test]
fn test_agent_session_full_fixture() {
    let json = fixture("agent_session.json");
    assert_roundtrip::<AgentSession>(&json);
}

#[test]
fn test_agent_session_minimal_fixture() {
    let json = fixture("agent_session_minimal.json");
    assert_roundtrip::<AgentSession>(&json);
}

#[test]
fn test_agent_session_field_values() {
    let json = fixture("agent_session.json");
    let session: AgentSession = serde_json::from_str(&json).unwrap();

    assert_eq!(session.vendor, "claude-code");
    assert_eq!(session.external_id, "session-abc-def-123");
    assert_eq!(session.name, "PR Review Agent");
    assert_eq!(session.status, AgentStatus::Executing);
    assert_eq!(session.heartbeat_interval_ms, Some(30000));
    assert_eq!(session.task.as_deref(), Some("Reviewing PR #123 for security issues"));

    let caps = session.capabilities.expect("capabilities should be present");
    assert_eq!(caps.len(), 2);
    assert!(caps.contains(&"code-review".to_string()));

    let tags = session.tags.expect("tags should be present");
    assert!(tags.contains(&"pr-fleet".to_string()));
}

#[test]
fn test_agent_session_minimal_optionals_absent() {
    let json = fixture("agent_session_minimal.json");
    let session: AgentSession = serde_json::from_str(&json).unwrap();

    assert_eq!(session.status, AgentStatus::Idle);
    assert!(session.capabilities.is_none());
    assert!(session.heartbeat_interval_ms.is_none());
    assert!(session.metadata.is_none());
    assert!(session.tags.is_none());
    assert!(session.task.is_none());
    assert!(session.metrics.is_none());
}

#[test]
fn test_agent_status_all_variants() {
    for variant in &["idle", "starting", "executing", "blocked", "completed", "failed", "unknown"] {
        let json = format!(r#""{variant}""#);
        let _: AgentStatus = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse agent status {variant}: {e}"));
    }
}

// ---------------------------------------------------------------------------
// PrFleetEntry fixtures
// ---------------------------------------------------------------------------

#[test]
fn test_pr_fleet_entry_fixture() {
    let json = fixture("pr_fleet_entry.json");
    assert_roundtrip::<PrFleetEntry>(&json);
}

#[test]
fn test_pr_fleet_entry_high_risk_fixture() {
    let json = fixture("pr_fleet_entry_high_risk.json");
    assert_roundtrip::<PrFleetEntry>(&json);
}

#[test]
fn test_pr_fleet_entry_field_values() {
    let json = fixture("pr_fleet_entry.json");
    let entry: PrFleetEntry = serde_json::from_str(&json).unwrap();

    assert_eq!(entry.repo, "miniforge-ai/miniforge");
    assert_eq!(entry.number, 101);
    assert!(entry.url.contains("pull/101"));
    assert_eq!(entry.branch, "feature/oauth2-login");
    assert_eq!(entry.status, PrStatus::Approved);
    assert_eq!(entry.ci_status, CiStatus::Passed);
    assert_eq!(entry.merge_order, 1);
    assert!(entry.depends_on.is_empty());
    assert_eq!(entry.blocks, vec![102, 103]);
    assert!((entry.readiness_score - 0.95).abs() < f64::EPSILON);
    assert_eq!(entry.risk.level, RiskLevel::Low);
    assert!((entry.risk.score - 0.15).abs() < f64::EPSILON);
    assert_eq!(entry.automation_tier, Some(AutomationTier::Full));
    assert_eq!(entry.additions, Some(250));
    assert_eq!(entry.deletions, Some(30));
}

#[test]
fn test_pr_fleet_entry_high_risk_values() {
    let json = fixture("pr_fleet_entry_high_risk.json");
    let entry: PrFleetEntry = serde_json::from_str(&json).unwrap();

    assert_eq!(entry.status, PrStatus::Draft);
    assert_eq!(entry.ci_status, CiStatus::Pending);
    assert_eq!(entry.depends_on, vec![101, 201]);
    assert!(entry.blocks.is_empty());
    assert!(entry.readiness_score < 0.3);
    assert_eq!(entry.risk.level, RiskLevel::Critical);
    assert!(entry.risk.score > 0.8);

    let factors = entry.risk.factors.expect("factors should be present");
    assert!(factors.contains(&"database-migration".to_string()));
    assert_eq!(entry.automation_tier, Some(AutomationTier::Manual));
}

#[test]
fn test_pr_status_all_variants() {
    for variant in &["draft", "open", "reviewing", "changes-requested",
                     "approved", "merging", "merged", "closed", "failed"] {
        let json = format!(r#""{variant}""#);
        let _: PrStatus = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse PR status {variant}: {e}"));
    }
}

#[test]
fn test_ci_status_all_variants() {
    for variant in &["pending", "running", "passed", "failed", "skipped"] {
        let json = format!(r#""{variant}""#);
        let _: CiStatus = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse CI status {variant}: {e}"));
    }
}

#[test]
fn test_risk_level_all_variants() {
    for variant in &["low", "medium", "high", "critical"] {
        let json = format!(r#""{variant}""#);
        let _: RiskLevel = serde_json::from_str(&json)
            .unwrap_or_else(|e| panic!("Failed to parse risk level {variant}: {e}"));
    }
}
