// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! PolicyEvaluation — records the outcome of evaluating policies against a PR.
//!
//! Mirrors the Malli schema `ai.miniforge.schema.supervisory/PolicyEvaluation`.
//! Required fields: eval/id, eval/pr-id, eval/result, eval/rules-applied,
//! eval/evaluated-at.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Possible outcomes of a policy evaluation.
///
/// Maps to the Clojure enum: :pass :fail :warn :skip
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum EvalResult {
    Pass,
    Fail,
    Warn,
    Skip,
}

/// Target type for the evaluation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum EvalTargetType {
    Pr,
    Artifact,
    WorkflowOutput,
}

/// Category of a policy violation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ViolationCategory {
    Style,
    Security,
    Testing,
    Documentation,
    Architecture,
    Process,
    Budget,
}

/// Severity level of a violation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ViolationSeverity {
    Info,
    Low,
    Medium,
    High,
    Critical,
}

/// A single policy violation within an evaluation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyViolation {
    /// Unique violation identity.
    #[serde(rename = "violation/id")]
    pub id: Uuid,

    /// Parent evaluation ID.
    #[serde(rename = "violation/eval-id")]
    pub eval_id: Uuid,

    /// Rule that was violated.
    #[serde(rename = "violation/rule")]
    pub rule: String,

    /// Category of the violation.
    #[serde(rename = "violation/category")]
    pub category: ViolationCategory,

    /// Severity of the violation.
    #[serde(rename = "violation/severity")]
    pub severity: ViolationSeverity,
}

/// A policy evaluation result.
///
/// Records the outcome of evaluating policies against a PR or artifact.
/// Open map — optional fields may be absent in JSON.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyEvaluation {
    /// Unique evaluation identity (UUID).
    #[serde(rename = "eval/id")]
    pub id: Uuid,

    /// PR identifier (format: "repo/number").
    #[serde(rename = "eval/pr-id")]
    pub pr_id: String,

    /// Evaluation outcome.
    #[serde(rename = "eval/result")]
    pub result: EvalResult,

    /// Policy rules that were evaluated.
    #[serde(rename = "eval/rules-applied")]
    pub rules_applied: Vec<String>,

    /// When the evaluation completed.
    #[serde(rename = "eval/evaluated-at")]
    pub evaluated_at: DateTime<Utc>,

    /// What type of entity was evaluated (optional).
    #[serde(rename = "eval/target-type", default, skip_serializing_if = "Option::is_none")]
    pub target_type: Option<EvalTargetType>,

    /// Whether the evaluation passed overall (optional).
    #[serde(rename = "eval/passed?", default, skip_serializing_if = "Option::is_none")]
    pub passed: Option<bool>,

    /// Policy packs that were applied (optional).
    #[serde(rename = "eval/packs-applied", default, skip_serializing_if = "Option::is_none")]
    pub packs_applied: Option<Vec<String>>,

    /// Specific violations found (optional).
    #[serde(rename = "eval/violations", default, skip_serializing_if = "Option::is_none")]
    pub violations: Option<Vec<PolicyViolation>>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_eval_result_serde() {
        let json = serde_json::to_string(&EvalResult::Pass).unwrap();
        assert_eq!(json, r#""pass""#);

        let parsed: EvalResult = serde_json::from_str(r#""warn""#).unwrap();
        assert_eq!(parsed, EvalResult::Warn);
    }

    #[test]
    fn test_policy_evaluation_minimal() {
        let eval = PolicyEvaluation {
            id: Uuid::nil(),
            pr_id: "my-repo/123".into(),
            result: EvalResult::Pass,
            rules_applied: vec!["no-force-push".into(), "require-tests".into()],
            evaluated_at: DateTime::parse_from_rfc3339("2026-04-10T12:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
            target_type: None,
            passed: None,
            packs_applied: None,
            violations: None,
        };

        let json = serde_json::to_string_pretty(&eval).unwrap();
        let roundtrip: PolicyEvaluation = serde_json::from_str(&json).unwrap();
        assert_eq!(eval, roundtrip);
    }
}
