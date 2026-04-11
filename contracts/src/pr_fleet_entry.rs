// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! PrFleetEntry — a PR within the fleet / merge train.
//!
//! Mirrors the PR maps used in the TUI persistence layer and
//! `components/pr-train`. Combines PR metadata, CI status,
//! risk assessment, and merge-train ordering.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// PR review/merge status.
///
/// Maps to: :draft :open :reviewing :changes-requested :approved
///          :merging :merged :closed :failed
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PrStatus {
    Draft,
    Open,
    Reviewing,
    ChangesRequested,
    Approved,
    Merging,
    Merged,
    Closed,
    Failed,
}

/// CI pipeline status.
///
/// Maps to: :pending :running :passed :failed :skipped
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum CiStatus {
    Pending,
    Running,
    Passed,
    Failed,
    Skipped,
}

/// Risk level for a PR.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RiskLevel {
    Low,
    Medium,
    High,
    Critical,
}

/// Automation tier classification.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AutomationTier {
    Full,
    Assisted,
    Manual,
}

/// Risk assessment for a PR.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrRisk {
    /// Numeric risk score.
    #[serde(rename = "risk/score")]
    pub score: f64,

    /// Categorical risk level.
    #[serde(rename = "risk/level")]
    pub level: RiskLevel,

    /// Contributing risk factors (optional).
    #[serde(rename = "risk/factors", default, skip_serializing_if = "Option::is_none")]
    pub factors: Option<Vec<String>>,
}

/// A PR entry within the fleet merge train.
///
/// Combines PR metadata, CI status, risk assessment,
/// dependency graph, and merge-train ordering.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrFleetEntry {
    /// Repository slug (e.g. "miniforge-ai/miniforge").
    #[serde(rename = "pr/repo")]
    pub repo: String,

    /// PR number.
    #[serde(rename = "pr/number")]
    pub number: i64,

    /// Full PR URL.
    #[serde(rename = "pr/url")]
    pub url: String,

    /// Branch name.
    #[serde(rename = "pr/branch")]
    pub branch: String,

    /// PR title.
    #[serde(rename = "pr/title")]
    pub title: String,

    /// Review/merge status.
    #[serde(rename = "pr/status")]
    pub status: PrStatus,

    /// CI pipeline status.
    #[serde(rename = "pr/ci-status")]
    pub ci_status: CiStatus,

    /// Position in merge train ordering.
    #[serde(rename = "pr/merge-order")]
    pub merge_order: i64,

    /// PR numbers this PR depends on.
    #[serde(rename = "pr/depends-on")]
    pub depends_on: Vec<i64>,

    /// PR numbers this PR blocks.
    #[serde(rename = "pr/blocks")]
    pub blocks: Vec<i64>,

    /// Readiness score (0.0–1.0).
    #[serde(rename = "pr/readiness-score")]
    pub readiness_score: f64,

    /// Risk assessment.
    #[serde(rename = "pr/risk")]
    pub risk: PrRisk,

    /// Automation tier classification (optional).
    #[serde(rename = "pr/automation-tier", default, skip_serializing_if = "Option::is_none")]
    pub automation_tier: Option<AutomationTier>,

    /// Lines added (optional).
    #[serde(rename = "pr/additions", default, skip_serializing_if = "Option::is_none")]
    pub additions: Option<i64>,

    /// Lines deleted (optional).
    #[serde(rename = "pr/deletions", default, skip_serializing_if = "Option::is_none")]
    pub deletions: Option<i64>,

    /// Cached policy evaluation results (optional).
    #[serde(rename = "pr/policy", default, skip_serializing_if = "Option::is_none")]
    pub policy: Option<HashMap<String, serde_json::Value>>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pr_status_serde() {
        let json = serde_json::to_string(&PrStatus::ChangesRequested).unwrap();
        assert_eq!(json, r#""changes-requested""#);

        let parsed: PrStatus = serde_json::from_str(r#""approved""#).unwrap();
        assert_eq!(parsed, PrStatus::Approved);
    }

    #[test]
    fn test_ci_status_serde() {
        let json = serde_json::to_string(&CiStatus::Passed).unwrap();
        assert_eq!(json, r#""passed""#);
    }

    #[test]
    fn test_pr_fleet_entry_roundtrip() {
        let entry = PrFleetEntry {
            repo: "miniforge-ai/miniforge".into(),
            number: 101,
            url: "https://github.com/miniforge-ai/miniforge/pull/101".into(),
            branch: "feature/auth".into(),
            title: "Add authentication module".into(),
            status: PrStatus::Approved,
            ci_status: CiStatus::Passed,
            merge_order: 1,
            depends_on: vec![],
            blocks: vec![102, 103],
            readiness_score: 0.95,
            risk: PrRisk {
                score: 0.2,
                level: RiskLevel::Low,
                factors: Some(vec!["small-diff".into()]),
            },
            automation_tier: Some(AutomationTier::Full),
            additions: Some(150),
            deletions: Some(20),
            policy: None,
        };

        let json = serde_json::to_string_pretty(&entry).unwrap();
        let roundtrip: PrFleetEntry = serde_json::from_str(&json).unwrap();
        assert_eq!(entry, roundtrip);
    }
}
