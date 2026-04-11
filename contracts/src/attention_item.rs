// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! AttentionItem — surfaces items needing human oversight.
//!
//! Mirrors the Malli schema `ai.miniforge.schema.supervisory/AttentionItem`.
//! Required fields: attention/id, attention/severity, attention/summary,
//! attention/source-type, attention/source-id.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Severity levels for attention items.
///
/// Maps to the Clojure enum: :info :low :medium :high :critical
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AttentionSeverity {
    Info,
    Low,
    Medium,
    High,
    Critical,
}

/// Source types that can generate attention items.
///
/// Maps to the Clojure enum: :workflow :policy :agent :system :human
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AttentionSourceType {
    Workflow,
    Policy,
    Agent,
    System,
    Human,
}

/// An attention item requiring human review.
///
/// Surfaces items needing oversight from any source in the system.
/// Open map — optional fields may be absent in JSON.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttentionItem {
    /// Unique attention item identity (UUID).
    #[serde(rename = "attention/id")]
    pub id: Uuid,

    /// Severity level.
    #[serde(rename = "attention/severity")]
    pub severity: AttentionSeverity,

    /// One-line description of what needs attention.
    #[serde(rename = "attention/summary")]
    pub summary: String,

    /// Type of entity that generated this attention item.
    #[serde(rename = "attention/source-type")]
    pub source_type: AttentionSourceType,

    /// Identifier of the entity that triggered attention.
    #[serde(rename = "attention/source-id")]
    pub source_id: String,

    /// When the attention item was derived (optional).
    #[serde(rename = "attention/derived-at", default, skip_serializing_if = "Option::is_none")]
    pub derived_at: Option<DateTime<Utc>>,

    /// When the attention item was created (optional).
    #[serde(rename = "attention/created-at", default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<DateTime<Utc>>,

    /// Whether the underlying condition has resolved (optional).
    #[serde(rename = "attention/resolved?", default, skip_serializing_if = "Option::is_none")]
    pub resolved: Option<bool>,

    /// Whether a human has acknowledged this item (optional).
    #[serde(rename = "attention/acknowledged?", default, skip_serializing_if = "Option::is_none")]
    pub acknowledged: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_severity_serde() {
        let json = serde_json::to_string(&AttentionSeverity::Critical).unwrap();
        assert_eq!(json, r#""critical""#);
    }

    #[test]
    fn test_source_type_serde() {
        let json = serde_json::to_string(&AttentionSourceType::Policy).unwrap();
        assert_eq!(json, r#""policy""#);
    }

    #[test]
    fn test_attention_item_roundtrip() {
        let item = AttentionItem {
            id: Uuid::nil(),
            severity: AttentionSeverity::High,
            summary: "Budget exceeded on workflow run".into(),
            source_type: AttentionSourceType::System,
            source_id: "workflow-abc-123".into(),
            derived_at: None,
            created_at: None,
            resolved: None,
            acknowledged: None,
        };

        let json = serde_json::to_string_pretty(&item).unwrap();
        let roundtrip: AttentionItem = serde_json::from_str(&json).unwrap();
        assert_eq!(item, roundtrip);
    }
}
