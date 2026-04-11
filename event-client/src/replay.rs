//! Startup event replay.
//!
//! On startup, before the live file watcher takes over, the client replays
//! every existing `.edn` file in the events directory.  Files are processed
//! in filename-sorted order, so timestamp-prefixed filenames (e.g.
//! `20260411T100000-task-started.edn`) replay in chronological sequence.
//!
//! All replayed events have [`ParsedEvent::is_replay`] set to `true` so
//! consumers can distinguish historical state reconstruction from live events.

use anyhow::Result;
use std::path::{Path, PathBuf};
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

use crate::events::ParsedEvent;
use crate::parser::parse_event_file;

// ─── Public API ───────────────────────────────────────────────────────────────

/// Replay all existing `.edn` files from `events_dir` in filename order.
///
/// Each successfully parsed event is sent to `tx` with `is_replay = true`.
/// Unparseable or unreadable files are logged and skipped so a single bad
/// file cannot block the rest of the replay.
///
/// Returns the total number of events successfully replayed.
pub async fn replay_events(events_dir: &Path, tx: &mpsc::Sender<ParsedEvent>) -> Result<usize> {
    let mut paths = collect_edn_files(events_dir)?;

    // Filename sort: timestamp-prefixed names (e.g. `20260411T100000-…`) become
    // chronological order automatically.
    paths.sort();

    info!(
        dir   = %events_dir.display(),
        files = paths.len(),
        "replaying existing events on startup"
    );

    let mut replayed = 0usize;

    for path in &paths {
        match std::fs::read_to_string(path) {
            Err(e) => {
                warn!(path = %path.display(), "could not read event file during replay: {e}");
            }
            Ok(content) => match parse_event_file(&content, path.clone(), true) {
                Err(e) => {
                    warn!(
                        path = %path.display(),
                        "skipping unparseable event during replay: {e}"
                    );
                }
                Ok(event) => {
                    debug!(
                        path = %path.display(),
                        kind = event.kind.as_keyword(),
                        id   = ?event.id,
                        "replaying event"
                    );
                    if tx.send(event).await.is_err() {
                        warn!("event channel closed during replay; stopping");
                        break;
                    }
                    replayed += 1;
                }
            },
        }
    }

    info!(replayed, "startup replay complete");
    Ok(replayed)
}

// ─── Internal helpers ─────────────────────────────────────────────────────────

/// Collect all `.edn` files directly inside `events_dir` (non-recursive).
/// Returns an empty vec if the directory does not yet exist.
fn collect_edn_files(events_dir: &Path) -> Result<Vec<PathBuf>> {
    if !events_dir.exists() {
        return Ok(Vec::new());
    }

    let mut paths = Vec::new();
    for entry in std::fs::read_dir(events_dir)? {
        let entry = entry?;
        let path  = entry.path();
        if path.is_file() && is_edn_file(&path) {
            paths.push(path);
        }
    }
    Ok(paths)
}

pub(crate) fn is_edn_file(path: &Path) -> bool {
    path.extension()
        .map(|ext| ext.eq_ignore_ascii_case("edn"))
        .unwrap_or(false)
}

// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn make_dir() -> TempDir {
        TempDir::new().expect("tempdir")
    }

    fn write_event(dir: &TempDir, name: &str, body: &str) {
        fs::write(dir.path().join(name), body).expect("write event");
    }

    // ── collect_edn_files ─────────────────────────────────────────────────────

    #[test]
    fn nonexistent_dir_returns_empty() {
        let paths = collect_edn_files(Path::new("/no/such/dir")).unwrap();
        assert!(paths.is_empty());
    }

    #[test]
    fn only_edn_files_are_returned() {
        let dir = make_dir();
        write_event(&dir, "a.edn",  "{:event/type :task/started}");
        write_event(&dir, "b.json", r#"{"type":"task"}"#);
        write_event(&dir, "c.txt",  "plain text");

        let paths = collect_edn_files(dir.path()).unwrap();
        assert_eq!(paths.len(), 1);
        assert!(paths[0].ends_with("a.edn"));
    }

    // ── replay_events ─────────────────────────────────────────────────────────

    #[tokio::test]
    async fn empty_directory_replays_zero_events() {
        let dir = make_dir();
        let (tx, _rx) = mpsc::channel(16);
        let n = replay_events(dir.path(), &tx).await.unwrap();
        assert_eq!(n, 0);
    }

    #[tokio::test]
    async fn nonexistent_directory_replays_zero_events() {
        let (tx, _rx) = mpsc::channel(16);
        let n = replay_events(Path::new("/nonexistent/miniforge/events"), &tx)
            .await
            .unwrap();
        assert_eq!(n, 0);
    }

    #[tokio::test]
    async fn events_replayed_in_filename_order() {
        let dir = make_dir();
        write_event(&dir, "001-a.edn", r#"{:event/type :task/started    :event/id "e1"}"#);
        write_event(&dir, "002-b.edn", r#"{:event/type :task/completed  :event/id "e2"}"#);
        write_event(&dir, "003-c.edn", r#"{:event/type :task/failed     :event/id "e3"}"#);

        let (tx, mut rx) = mpsc::channel(16);
        let n = replay_events(dir.path(), &tx).await.unwrap();
        assert_eq!(n, 3);

        for expected_id in ["e1", "e2", "e3"] {
            let ev = rx.recv().await.expect("event");
            assert_eq!(ev.id.as_deref(), Some(expected_id));
            assert!(ev.is_replay, "is_replay must be true during replay");
        }
    }

    #[tokio::test]
    async fn bad_edn_file_is_skipped_others_still_replayed() {
        let dir = make_dir();
        write_event(&dir, "001-good.edn",  r#"{:event/type :task/started :event/id "ok"}"#);
        write_event(&dir, "002-bad.edn",   "this is not valid edn {{{{");
        write_event(&dir, "003-good2.edn", r#"{:event/type :task/failed  :event/id "ok2"}"#);

        let (tx, mut rx) = mpsc::channel(16);
        let n = replay_events(dir.path(), &tx).await.unwrap();
        assert_eq!(n, 2);

        let ev1 = rx.recv().await.unwrap();
        assert_eq!(ev1.id.as_deref(), Some("ok"));
        let ev2 = rx.recv().await.unwrap();
        assert_eq!(ev2.id.as_deref(), Some("ok2"));
    }

    #[tokio::test]
    async fn non_edn_files_are_not_replayed() {
        let dir = make_dir();
        write_event(&dir, "event.edn",  r#"{:event/type :task/started :event/id "e"}"#);
        write_event(&dir, "other.json", r#"{"type":"task"}"#);

        let (tx, mut rx) = mpsc::channel(16);
        let n = replay_events(dir.path(), &tx).await.unwrap();
        assert_eq!(n, 1);

        let ev = rx.recv().await.unwrap();
        assert_eq!(ev.id.as_deref(), Some("e"));
    }
}
