//! Live file-system watcher for new `.edn` event files.
//!
//! Uses the [`notify`] crate (native backends: inotify on Linux, FSEvents on
//! macOS, ReadDirectoryChangesW on Windows) to watch the events directory for
//! newly created `.edn` files.  Each new file is read, parsed, and forwarded
//! to the shared [`tokio::sync::mpsc`] channel.
//!
//! ## Design notes
//!
//! * Only `Create` events are processed.  Miniforge agents write event files
//!   atomically (write-to-temp then rename), so a single `Create` always
//!   represents a complete, fully-written file.
//! * The `notify` callback is synchronous; we bridge it to the async world via
//!   a `std::sync::mpsc` → `std::thread::spawn` → `tokio::sync::mpsc::blocking_send`
//!   chain so the tokio executor is never blocked.
//! * Keeping the returned [`WatcherHandle`] alive is required for watching to
//!   continue; dropping it unregisters the watch and signals the background
//!   thread to exit.

use anyhow::Result;
use notify::{Config, Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use std::path::{Path, PathBuf};
use std::sync::mpsc as std_mpsc;
use tokio::sync::mpsc as tokio_mpsc;
use tracing::{debug, error, info, warn};

use crate::events::ParsedEvent;
use crate::parser::parse_event_file;
use crate::replay::is_edn_file;

// ─── Public API ───────────────────────────────────────────────────────────────

/// RAII guard that keeps the underlying [`RecommendedWatcher`] alive.
///
/// Dropping this value stops watching the directory.  When the watcher is
/// dropped, the handler closure (which owns the sync sender) is also dropped,
/// causing `notify_rx.recv()` in the background thread to return `Err` and
/// the thread to exit cleanly.
pub struct WatcherHandle {
    _watcher: RecommendedWatcher,
}

/// Start watching `events_dir` for newly created `.edn` files.
///
/// Parsed events are sent to `tx`.  The returned [`WatcherHandle`] must be
/// kept alive for watching to continue.
pub fn start_watcher(
    events_dir: &Path,
    tx: tokio_mpsc::Sender<ParsedEvent>,
) -> Result<WatcherHandle> {
    // ── Sync bridge: notify callback → std channel ───────────────────────────
    //
    // `notify` delivers events via a synchronous callback; we funnel them
    // through a std::mpsc channel so we can process them on a dedicated thread
    // without blocking the notify backend.
    let (notify_tx, notify_rx) = std_mpsc::channel::<notify::Result<Event>>();

    let mut watcher = RecommendedWatcher::new(
        move |result| {
            // Ignore send errors: they only happen when the background thread
            // has already exited (i.e., we're shutting down).
            let _ = notify_tx.send(result);
        },
        Config::default(),
    )?;

    watcher.watch(events_dir, RecursiveMode::NonRecursive)?;

    info!(
        dir = %events_dir.display(),
        "live watcher started — watching for new .edn event files"
    );

    // ── Background thread: std channel → tokio mpsc ──────────────────────────
    //
    // Runs a blocking loop on a dedicated OS thread so we never tie up tokio's
    // worker thread pool.  `blocking_send` is valid from non-async threads.
    let events_dir_owned = events_dir.to_path_buf();

    std::thread::Builder::new()
        .name("miniforge-event-watcher".into())
        .spawn(move || {
            watcher_thread_loop(notify_rx, &events_dir_owned, &tx);
        })?;

    // Return a handle that owns the watcher.  When the handle is dropped, the
    // watcher is dropped → the closure above is dropped → `notify_tx` is
    // dropped → `notify_rx.recv()` returns `Err` → the thread exits cleanly.
    Ok(WatcherHandle { _watcher: watcher })
}

// ─── Internal implementation ──────────────────────────────────────────────────

/// Main loop of the background watcher thread.
///
/// Receives raw [`notify::Event`]s from `rx`, filters for `.edn` file
/// creations, parses them, and forwards them to the tokio channel.
fn watcher_thread_loop(
    rx: std_mpsc::Receiver<notify::Result<Event>>,
    _events_dir: &Path,
    tx: &tokio_mpsc::Sender<ParsedEvent>,
) {
    while let Ok(result) = rx.recv() {
        match result {
            Err(e) => {
                error!(error = %e, "file watcher backend error");
            }
            Ok(event) => {
                handle_fs_event(event, tx);
            }
        }
    }
    debug!("watcher thread exiting — notify channel closed");
}

/// Dispatch a single filesystem event.
///
/// Only newly-created `.edn` files are processed; everything else is silently
/// ignored.
fn handle_fs_event(event: Event, tx: &tokio_mpsc::Sender<ParsedEvent>) {
    // Atomic rename-based writes produce a Create event for the final path,
    // which is the only case we need to handle.
    if !matches!(event.kind, EventKind::Create(_)) {
        return;
    }

    for path in event.paths {
        if !is_edn_file(&path) {
            continue;
        }
        debug!(path = %path.display(), "new .edn file detected");
        process_new_file(&path, tx);
    }
}

/// Read, parse, and emit a single newly-created event file.
fn process_new_file(path: &Path, tx: &tokio_mpsc::Sender<ParsedEvent>) {
    let content = match std::fs::read_to_string(path) {
        Err(e) => {
            warn!(path = %path.display(), "failed to read new event file: {e}");
            return;
        }
        Ok(c) => c,
    };

    let event = match parse_event_file(&content, path.to_path_buf(), false) {
        Err(e) => {
            warn!(path = %path.display(), "failed to parse new event file: {e}");
            return;
        }
        Ok(ev) => ev,
    };

    debug!(
        path = %path.display(),
        kind = event.kind.as_keyword(),
        id   = ?event.id,
        "emitting live event"
    );

    // `blocking_send` blocks if the channel buffer is full and returns `Err`
    // only when the receiver has been dropped.
    if tx.blocking_send(event).is_err() {
        warn!("event channel closed — live watcher stopping");
    }
}

// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;
    use tokio::sync::mpsc;

    fn make_dir() -> TempDir {
        TempDir::new().expect("tempdir")
    }

    // Smoke test: watcher can be started without error.
    #[tokio::test]
    async fn watcher_starts_without_error() {
        let dir = make_dir();
        let (tx, _rx) = mpsc::channel(16);
        let result = start_watcher(dir.path(), tx);
        assert!(result.is_ok(), "start_watcher failed: {:?}", result.err());
    }

    // Integration: a file written after the watcher starts is received.
    #[tokio::test]
    async fn new_edn_file_is_emitted() {
        let dir = make_dir();
        let (tx, mut rx) = mpsc::channel(16);

        let _handle = start_watcher(dir.path(), tx).expect("watcher");

        // Give the OS watcher a moment to register
        tokio::time::sleep(tokio::time::Duration::from_millis(150)).await;

        // Atomic write: matches how Clojure agents emit events
        let tmp_path   = dir.path().join("event.edn.tmp");
        let final_path = dir.path().join("event.edn");
        fs::write(&tmp_path, r#"{:event/type :task/completed :event/id "live-1"}"#)
            .expect("write tmp");
        fs::rename(&tmp_path, &final_path).expect("rename");

        // Allow up to 3 s for the event to propagate through the OS backend
        let event = tokio::time::timeout(
            tokio::time::Duration::from_secs(3),
            rx.recv(),
        )
        .await
        .expect("timed out waiting for live event")
        .expect("channel unexpectedly closed");

        assert_eq!(event.id.as_deref(), Some("live-1"));
        assert!(!event.is_replay, "live events must have is_replay = false");
        assert!(matches!(event.kind, crate::events::EventType::TaskCompleted));
    }

    // Non-.edn files must be silently ignored.
    #[tokio::test]
    async fn non_edn_files_are_ignored() {
        let dir = make_dir();
        let (tx, mut rx) = mpsc::channel(16);
        let _handle = start_watcher(dir.path(), tx).expect("watcher");
        tokio::time::sleep(tokio::time::Duration::from_millis(150)).await;

        fs::write(dir.path().join("notes.txt"), "not an event").expect("write");
        fs::write(dir.path().join("data.json"), r#"{"k":"v"}"#).expect("write");

        // Nothing should arrive; wait 400 ms to be sure
        let result = tokio::time::timeout(
            tokio::time::Duration::from_millis(400),
            rx.recv(),
        )
        .await;

        assert!(result.is_err(), "expected timeout — non-EDN files should not emit events");
    }
}
