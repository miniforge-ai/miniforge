// Title: Miniforge.ai
// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! EDN parser for miniforge events.
//!
//! Parses raw EDN text (one event per line) into strongly-typed [`Event`] structs.
//! The Clojure event stream writes events as `(pr-str event)` followed by `\n`,
//! so each line is a complete EDN map.

use std::collections::BTreeMap;

use edn_rs::Edn;

use crate::event::{EdnValue, Event, EventType};

/// Errors that can occur during event parsing.
#[derive(Debug, thiserror::Error)]
pub enum ParseError {
    #[error("EDN parse error: {0}")]
    EdnParse(String),

    #[error("expected EDN map, got: {0}")]
    NotAMap(String),

    #[error("missing required field: {0}")]
    MissingField(&'static str),

    #[error("field type mismatch for {field}: expected {expected}, got {actual}")]
    TypeMismatch {
        field: &'static str,
        expected: &'static str,
        actual: String,
    },
}

/// Parse a single line of EDN into an [`Event`].
///
/// # Arguments
/// * `line` — One line from a `.edn` event file (a complete EDN map).
/// * `source_file` — Optional path to the source file for diagnostics.
///
/// # Errors
/// Returns [`ParseError`] if the line is not a valid EDN map or lacks required fields.
pub fn parse_event(line: &str, source_file: Option<&str>) -> Result<Event, ParseError> {
    let trimmed = line.trim();
    if trimmed.is_empty() {
        return Err(ParseError::EdnParse("empty line".to_string()));
    }

    let edn: Edn = edn_rs::from_str(trimmed).map_err(|e| ParseError::EdnParse(e.to_string()))?;

    let map = match &edn {
        Edn::Map(_) => edn_to_btree(&edn),
        _ => return Err(ParseError::NotAMap(format!("{:?}", edn))),
    };

    // Extract required fields
    let event_type_str = get_string(&map, "event/type")
        .or_else(|| get_keyword(&map, "event/type"))
        .ok_or(ParseError::MissingField(":event/type"))?;
    let event_type = EventType::from_keyword(&event_type_str);

    let id = get_string(&map, "event/id")
        .or_else(|| get_uuid(&map, "event/id"))
        .ok_or(ParseError::MissingField(":event/id"))?;

    let timestamp = get_string(&map, "event/timestamp")
        .or_else(|| get_inst(&map, "event/timestamp"))
        .ok_or(ParseError::MissingField(":event/timestamp"))?;

    let version = get_string(&map, "event/version")
        .ok_or(ParseError::MissingField(":event/version"))?;

    let sequence_number = get_int(&map, "event/sequence-number")
        .ok_or(ParseError::MissingField(":event/sequence-number"))?;

    let workflow_id = get_string(&map, "workflow/id")
        .or_else(|| get_uuid(&map, "workflow/id"))
        .ok_or(ParseError::MissingField(":workflow/id"))?;

    let message = get_string(&map, "message")
        .ok_or(ParseError::MissingField(":message"))?;

    // Extract optional envelope fields
    let workflow_phase = get_string(&map, "workflow/phase")
        .or_else(|| get_keyword(&map, "workflow/phase"));
    let agent_id = get_string(&map, "agent/id")
        .or_else(|| get_keyword(&map, "agent/id"));
    let agent_instance_id = get_string(&map, "agent/instance-id")
        .or_else(|| get_uuid(&map, "agent/instance-id"));
    let parent_id = get_string(&map, "event/parent-id")
        .or_else(|| get_uuid(&map, "event/parent-id"));

    // Collect remaining fields into extra
    let known_keys: &[&str] = &[
        "event/type",
        "event/id",
        "event/timestamp",
        "event/version",
        "event/sequence-number",
        "workflow/id",
        "message",
        "workflow/phase",
        "agent/id",
        "agent/instance-id",
        "event/parent-id",
    ];
    let extra: BTreeMap<String, EdnValue> = map
        .iter()
        .filter(|(k, _)| !known_keys.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();

    Ok(Event {
        event_type,
        id,
        timestamp,
        version,
        sequence_number,
        workflow_id,
        message,
        workflow_phase,
        agent_id,
        agent_instance_id,
        parent_id,
        extra,
        source_file: source_file.map(String::from),
    })
}

/// Parse all events from a multi-line EDN string.
///
/// Skips blank lines and returns errors inline. Each line is parsed independently.
pub fn parse_events(
    content: &str,
    source_file: Option<&str>,
) -> Vec<Result<Event, ParseError>> {
    content
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| parse_event(line, source_file))
        .collect()
}

// ── Internal helpers ────────────────────────────────────────────────────────

/// Convert an Edn value to our EdnValue representation.
fn edn_to_edn_value(edn: &Edn) -> EdnValue {
    match edn {
        Edn::Nil => EdnValue::Nil,
        Edn::Bool(b) => EdnValue::Bool(*b),
        Edn::Int(n) => EdnValue::Int(*n),
        Edn::UInt(n) => EdnValue::Int(*n as i64),
        Edn::Double(d) => {
            // Double wraps OrderedFloat<f64>; use Display for safe extraction
            let s = d.to_string();
            EdnValue::Float(s.parse::<f64>().unwrap_or(0.0))
        }
        Edn::Rational(r) => EdnValue::String(r.clone()),
        Edn::Str(s) => EdnValue::String(s.clone()),
        Edn::Key(k) => {
            // Keys in edn-rs include the `:` prefix
            EdnValue::Keyword(strip_colon(k).to_string())
        }
        Edn::Symbol(s) => EdnValue::Symbol(s.clone()),
        Edn::Uuid(u) => EdnValue::Uuid(u.clone()),
        Edn::Inst(i) => EdnValue::Inst(i.clone()),
        Edn::Char(c) => EdnValue::String(c.to_string()),
        Edn::Vector(v) => {
            let items: Vec<EdnValue> = v.0.iter().map(edn_to_edn_value).collect();
            EdnValue::Vector(items)
        }
        Edn::List(l) => {
            let items: Vec<EdnValue> = l.0.iter().map(edn_to_edn_value).collect();
            EdnValue::Vector(items)
        }
        Edn::Set(s) => {
            let items: Vec<EdnValue> = s.0.iter().map(edn_to_edn_value).collect();
            EdnValue::Set(items)
        }
        Edn::Map(m) => {
            let entries: BTreeMap<String, EdnValue> = m
                .0
                .iter()
                .map(|(k, v)| {
                    // Map keys in edn-rs are strings; strip `:` prefix for keywords
                    let key = strip_colon(k).to_string();
                    (key, edn_to_edn_value(v))
                })
                .collect();
            EdnValue::Map(entries)
        }
        Edn::Empty => EdnValue::Nil,
        // NamespacedMap, Tagged, and future variants
        _ => EdnValue::String(format!("{}", edn)),
    }
}

/// Convert a top-level Edn map to a BTreeMap<String, EdnValue>.
///
/// In edn-rs, `Map.0` is a `BTreeMap<String, Edn>` where the keys are the
/// raw EDN representation of map keys (e.g., `":event/type"` for keyword keys).
/// We strip the leading `:` so lookups use plain `"event/type"`.
fn edn_to_btree(edn: &Edn) -> BTreeMap<String, EdnValue> {
    match edn {
        Edn::Map(m) => m
            .0
            .iter()
            .map(|(k, v)| {
                let key = strip_colon(k).to_string();
                (key, edn_to_edn_value(v))
            })
            .collect(),
        _ => BTreeMap::new(),
    }
}

/// Strip a leading `:` from a string (common in EDN keyword representations).
fn strip_colon(s: &str) -> &str {
    s.strip_prefix(':').unwrap_or(s)
}

/// Get a string value from the map for the given key.
fn get_string(map: &BTreeMap<String, EdnValue>, key: &str) -> Option<String> {
    match map.get(key) {
        Some(EdnValue::String(s)) => Some(s.clone()),
        _ => None,
    }
}

/// Get a keyword value as string from the map.
fn get_keyword(map: &BTreeMap<String, EdnValue>, key: &str) -> Option<String> {
    match map.get(key) {
        Some(EdnValue::Keyword(s)) => Some(s.clone()),
        _ => None,
    }
}

/// Get a UUID value as string from the map.
fn get_uuid(map: &BTreeMap<String, EdnValue>, key: &str) -> Option<String> {
    match map.get(key) {
        Some(EdnValue::Uuid(s)) => Some(s.clone()),
        _ => None,
    }
}

/// Get an inst value as string from the map.
fn get_inst(map: &BTreeMap<String, EdnValue>, key: &str) -> Option<String> {
    match map.get(key) {
        Some(EdnValue::Inst(s)) => Some(s.clone()),
        _ => None,
    }
}

/// Get an integer value from the map.
fn get_int(map: &BTreeMap<String, EdnValue>, key: &str) -> Option<i64> {
    match map.get(key) {
        Some(EdnValue::Int(n)) => Some(*n),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_edn() -> &'static str {
        r#"{:event/type :workflow/started, :event/id #uuid "550e8400-e29b-41d4-a716-446655440000", :event/timestamp #inst "2026-04-10T12:00:00.000Z", :event/version "1.0.0", :event/sequence-number 0, :workflow/id #uuid "660e8400-e29b-41d4-a716-446655440000", :message "Workflow started", :workflow/spec {:intent "implement feature"}}"#
    }

    #[test]
    fn parse_valid_event() {
        let result = parse_event(sample_edn(), Some("test.edn"));
        assert!(result.is_ok(), "parse failed: {:?}", result.err());
        let event = result.unwrap();
        assert_eq!(event.event_type, EventType::WorkflowStarted);
        assert_eq!(event.version, "1.0.0");
        assert_eq!(event.sequence_number, 0);
        assert_eq!(event.message, "Workflow started");
        assert!(event.source_file.as_deref() == Some("test.edn"));
        // workflow/spec should be in extra
        assert!(event.extra.contains_key("workflow/spec"));
    }

    #[test]
    fn parse_empty_line() {
        assert!(parse_event("", None).is_err());
        assert!(parse_event("   ", None).is_err());
    }

    #[test]
    fn parse_invalid_edn() {
        assert!(parse_event("{broken edn", None).is_err());
    }

    #[test]
    fn parse_non_map() {
        assert!(parse_event("42", None).is_err());
        assert!(parse_event("[:vector]", None).is_err());
    }

    #[test]
    fn parse_multiple_events() {
        let content = format!(
            "{}\n{}\n\n",
            sample_edn(),
            r#"{:event/type :agent/started, :event/id #uuid "550e8400-e29b-41d4-a716-446655440001", :event/timestamp #inst "2026-04-10T12:00:01.000Z", :event/version "1.0.0", :event/sequence-number 1, :workflow/id #uuid "660e8400-e29b-41d4-a716-446655440000", :agent/id :planner, :message "Agent started"}"#
        );
        let results = parse_events(&content, None);
        assert_eq!(results.len(), 2);
        assert!(results[0].is_ok());
        assert!(results[1].is_ok());
        let e1 = results[1].as_ref().unwrap();
        assert_eq!(e1.event_type, EventType::AgentStarted);
        assert_eq!(e1.agent_id.as_deref(), Some("planner"));
    }

    #[test]
    fn strip_colon_works() {
        assert_eq!(strip_colon(":event/type"), "event/type");
        assert_eq!(strip_colon("plain-key"), "plain-key");
        assert_eq!(strip_colon(""), "");
    }
}
