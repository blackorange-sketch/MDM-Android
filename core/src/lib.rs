//! The bridge between the Android app and Mister Download Manager's engine and torrents.
//!
//! The app talks to it through two JNI calls (`Native.init`, `Native.call`). `call` takes a
//! method name and a JSON object and answers with JSON, so the Kotlin side stays thin and the
//! interface can grow without new JNI signatures.
//!
//! Ids the app sees: `http:<n>` (engine download), `bt:<info hash>` (torrent) and `pend:<n>`
//! (a magnet or .torrent whose file list is still being fetched).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use mdm_engine::state::state_path;
use mdm_engine::download::State;
use mdm_engine::{Destination, Download, Options, Request};
use mdm_torrent::{Config, Phase, Source, Torrents};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

const METADATA_WAIT: Duration = Duration::from_secs(90);

struct Core {
    rt: tokio::runtime::Runtime,
    torrents: Torrents,
    /// Where new downloads go (the app can change it once it has access to the public folder).
    dir: Mutex<PathBuf>,
    /// The app's private folder: our list, librqbit's resume data.
    data: PathBuf,
    items: Mutex<Items>,
}

#[derive(Default)]
struct Items {
    next: u64,
    http: Vec<HttpItem>,
    /// Torrent names by info hash (librqbit only knows the hash after a restart).
    names: HashMap<String, String>,
    pending: Vec<Pending>,
    /// The list as last written, to write only when it changed.
    saved: String,
}

struct HttpItem {
    id: u64,
    url: String,
    dir: PathBuf,
    file: Option<PathBuf>,
    dl: Option<Download>,
    status: &'static str,
    error: Option<String>,
    size: Option<u64>,
    downloaded: u64,
    speed: u64,
}

struct Pending {
    id: u64,
    label: String,
    error: Option<String>,
}

#[derive(Serialize, Deserialize, Default)]
struct Saved {
    next: u64,
    http: Vec<SavedHttp>,
    names: HashMap<String, String>,
}

#[derive(Serialize, Deserialize)]
struct SavedHttp {
    id: u64,
    url: String,
    dir: PathBuf,
    file: Option<PathBuf>,
    done: bool,
    size: Option<u64>,
}

static CORE: OnceLock<Core> = OnceLock::new();

fn core() -> Result<&'static Core, String> {
    CORE.get().ok_or_else(|| "the core is not initialized".to_owned())
}

// ---------------------------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------------------------

/// `Native.initTls(context)`: gives the TLS verifier the app's context, to check certificates with
/// Android's own trust store. Must run before any connection. (rustls-platform-verifier 0.7 uses
/// jni 0.22, the rest of this bridge jni 0.21: hence the two crates.)
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_blackorange_mdm_Native_initTls<'l>(
    mut unowned_env: jni22::EnvUnowned<'l>,
    _class: jni22::objects::JClass<'l>,
    context: jni22::objects::JObject<'l>,
) {
    let outcome = unowned_env.with_env(|env| -> Result<(), jni22::errors::Error> {
        rustls_platform_verifier::android::init_with_env(env, context)
    });
    outcome.resolve::<jni22::errors::ThrowRuntimeExAndDefault>()
}

/// `Native.init(dataDir, downloadDir): String` — an empty string, or the error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_blackorange_mdm_Native_init<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    data_dir: JString<'l>,
    download_dir: JString<'l>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let data: String = env.get_string(&data_dir).map_err(|e| e.to_string())?.into();
        let dir: String = env.get_string(&download_dir).map_err(|e| e.to_string())?.into();
        init(PathBuf::from(data), PathBuf::from(dir))
    })();
    to_jstring(&mut env, result.err().unwrap_or_default())
}

/// `Native.call(method, argsJson): String` — JSON: the answer, or `{"error": "..."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_blackorange_mdm_Native_call<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    method: JString<'l>,
    args: JString<'l>,
) -> jstring {
    let method: String = env.get_string(&method).map(Into::into).unwrap_or_default();
    let args: String = env.get_string(&args).map(Into::into).unwrap_or_default();
    let out = std::panic::catch_unwind(|| dispatch(&method, &args))
        .unwrap_or_else(|_| Err("internal error (panic)".to_owned()));
    let text = match out {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, text)
}

fn to_jstring(env: &mut JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ---------------------------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------------------------

fn init(data: PathBuf, dir: PathBuf) -> Result<(), String> {
    if CORE.get().is_some() {
        return Ok(());
    }
    std::fs::create_dir_all(&data).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;
    let cfg = Config::new(data.join("torrents"));
    let torrents = rt
        .block_on(Torrents::start(cfg))
        .map_err(|e| format!("torrent session: {e}"))?;

    let mut items = Items::default();
    if let Ok(text) = std::fs::read_to_string(data.join("downloads.json"))
        && let Ok(saved) = serde_json::from_str::<Saved>(&text)
    {
        items.next = saved.next;
        items.names = saved.names;
        for s in saved.http {
            items.http.push(HttpItem {
                id: s.id,
                url: s.url,
                dir: s.dir,
                file: s.file,
                dl: None,
                // What was running before the app closed waits for the person to start it.
                status: if s.done { "done" } else { "paused" },
                error: None,
                size: s.size,
                downloaded: if s.done { s.size.unwrap_or(0) } else { 0 },
                speed: 0,
            });
        }
    }
    let core = Core {
        rt,
        torrents,
        dir: Mutex::new(dir),
        data,
        items: Mutex::new(items),
    };
    CORE.set(core).map_err(|_| "already initialized".to_owned())
}

// ---------------------------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------------------------

fn dispatch(method: &str, args: &str) -> Result<Value, String> {
    let c = core()?;
    let a: Value = if args.trim().is_empty() {
        Value::Null
    } else {
        serde_json::from_str(args).map_err(|e| format!("bad arguments: {e}"))?
    };
    let text = |key: &str| -> Result<String, String> {
        a.get(key)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| format!("missing \"{key}\""))
    };
    match method {
        // A link: http(s)/ftp go to the engine, a magnet to the torrent session.
        "add" => add(c, text("link")?.trim()),
        "add_torrent_file" => add_torrent_file(c, Path::new(&text("path")?)),
        "list" => Ok(list(c)),
        "pause" => pause(c, &text("id")?),
        "resume" => resume(c, &text("id")?),
        "remove" => {
            let files = a.get("delete_files").and_then(Value::as_bool).unwrap_or(false);
            remove(c, &text("id")?, files)
        }
        "set_download_dir" => {
            let dir = PathBuf::from(text("path")?);
            std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            *c.dir.lock().unwrap() = dir;
            Ok(json!({ "ok": true }))
        }
        "set_limits" => {
            // Bytes per second for torrents; 0 or missing: unlimited.
            let n = |k: &str| a.get(k).and_then(Value::as_u64).filter(|v| *v > 0);
            c.torrents.set_download_limit(n("down"));
            c.torrents.set_upload_limit(n("up"));
            Ok(json!({ "ok": true }))
        }
        other => Err(format!("unknown method \"{other}\"")),
    }
}

fn add(c: &'static Core, link: &str) -> Result<Value, String> {
    if let Some(src) = Source::magnet(link) {
        return Ok(add_torrent(c, src, "Magnet".to_owned()));
    }
    let url = url::Url::parse(link).map_err(|_| "this is not a link".to_owned())?;
    if !mdm_engine::supported(&url) {
        return Err("this kind of link is not supported".to_owned());
    }
    let mut items = c.items.lock().unwrap();
    items.next += 1;
    let id = items.next;
    let mut it = HttpItem {
        id,
        url: url.to_string(),
        dir: c.dir.lock().unwrap().clone(),
        file: None,
        dl: None,
        status: "connecting",
        error: None,
        size: None,
        downloaded: 0,
        speed: 0,
    };
    start_http(c, &mut it);
    items.http.push(it);
    save(c, &mut items);
    Ok(json!({ "id": format!("http:{id}") }))
}

fn add_torrent_file(c: &'static Core, path: &Path) -> Result<Value, String> {
    let bytes = mdm_torrent::read_file(path).map_err(|e| e.to_string())?;
    let label = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "Torrent".to_owned());
    Ok(add_torrent(c, Source::File(bytes), label))
}

/// Looks the torrent up (a magnet asks the swarm) and starts it, in the background.
fn add_torrent(c: &'static Core, src: Source, label: String) -> Value {
    let id = {
        let mut items = c.items.lock().unwrap();
        items.next += 1;
        let id = items.next;
        items.pending.push(Pending {
            id,
            label,
            error: None,
        });
        id
    };
    c.rt.spawn(async move {
        let res = resolve_and_start(c, &src).await;
        let mut items = c.items.lock().unwrap();
        match res {
            Ok((hash, name)) => {
                items.names.insert(hash, name);
                items.pending.retain(|p| p.id != id);
                save(c, &mut items);
            }
            Err(e) => {
                if let Some(p) = items.pending.iter_mut().find(|p| p.id == id) {
                    p.error = Some(e);
                }
            }
        }
    });
    json!({ "id": format!("pend:{id}") })
}

async fn resolve_and_start(c: &Core, src: &Source) -> Result<(String, String), String> {
    let meta = c
        .torrents
        .metadata(src, METADATA_WAIT, None)
        .await
        .map_err(|e| e.to_string())?;
    let folder = meta.folder_in(&c.dir.lock().unwrap());
    let hash = c
        .torrents
        .add(&meta.torrent, &folder, None, meta.peers.clone(), false)
        .await
        .map_err(|e| e.to_string())?;
    Ok((hash, meta.name))
}

fn list(c: &Core) -> Value {
    let mut items = c.items.lock().unwrap();
    let mut out = Vec::new();
    for it in items.http.iter_mut() {
        refresh(it);
        let name = it
            .file
            .as_ref()
            .and_then(|f| f.file_name())
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| it.url.clone());
        out.push(json!({
            "id": format!("http:{}", it.id),
            "kind": "http",
            "name": name,
            "status": it.status,
            "done": it.downloaded,
            "total": it.size,
            "speed": it.speed,
            "error": it.error,
        }));
    }
    for p in &items.pending {
        out.push(json!({
            "id": format!("pend:{}", p.id),
            "kind": "torrent",
            "name": p.label,
            "status": if p.error.is_some() { "error" } else { "resolving" },
            "done": 0,
            "total": null,
            "speed": 0,
            "error": p.error,
        }));
    }
    for hash in c.torrents.hashes() {
        let Ok(s) = c.torrents.stats(&hash) else {
            continue;
        };
        let name = items
            .names
            .get(&hash)
            .cloned()
            .unwrap_or_else(|| hash.chars().take(8).collect());
        out.push(json!({
            "id": format!("bt:{hash}"),
            "kind": "torrent",
            "name": name,
            "status": match s.phase {
                Phase::Checking => "checking",
                Phase::Downloading => "downloading",
                Phase::Finished => "done",
                Phase::Paused => "paused",
                Phase::Error => "error",
            },
            "done": s.done,
            "total": s.total,
            "speed": s.down_speed as u64,
            "up_speed": s.up_speed as u64,
            "peers": s.peers_live,
            "error": s.error,
        }));
    }
    save(c, &mut items);
    Value::Array(out)
}

fn pause(c: &Core, id: &str) -> Result<Value, String> {
    if let Some(hash) = id.strip_prefix("bt:") {
        c.rt.block_on(c.torrents.pause(hash)).map_err(|e| e.to_string())?;
    } else if let Some(n) = http_id(id) {
        let mut items = c.items.lock().unwrap();
        let it = items.http.iter_mut().find(|i| i.id == n).ok_or("not in the list")?;
        if let Some(dl) = it.dl.take() {
            dl.cancel();
            it.status = "paused";
            it.speed = 0;
        }
    }
    Ok(json!({ "ok": true }))
}

fn resume(c: &'static Core, id: &str) -> Result<Value, String> {
    if let Some(hash) = id.strip_prefix("bt:") {
        c.rt.block_on(c.torrents.resume(hash)).map_err(|e| e.to_string())?;
    } else if let Some(n) = http_id(id) {
        let mut items = c.items.lock().unwrap();
        let it = items.http.iter_mut().find(|i| i.id == n).ok_or("not in the list")?;
        if it.dl.is_none() && it.status != "done" {
            it.error = None;
            start_http(c, it);
        }
    }
    Ok(json!({ "ok": true }))
}

fn remove(c: &Core, id: &str, delete_files: bool) -> Result<Value, String> {
    if let Some(hash) = id.strip_prefix("bt:") {
        c.rt
            .block_on(c.torrents.remove(hash, delete_files))
            .map_err(|e| e.to_string())?;
        c.items.lock().unwrap().names.remove(hash);
    } else if let Some(n) = http_id(id) {
        let mut items = c.items.lock().unwrap();
        if let Some(pos) = items.http.iter().position(|i| i.id == n) {
            let it = items.http.remove(pos);
            if let Some(dl) = &it.dl {
                dl.cancel();
            }
            if let Some(file) = &it.file {
                // The unfinished download's own files always go; the finished file only when asked.
                let _ = std::fs::remove_file(state_path(file));
                let _ = std::fs::remove_file(mdm_engine::naming::part_path(file));
                if delete_files {
                    let _ = std::fs::remove_file(file);
                }
            }
        }
    } else if let Some(n) = id.strip_prefix("pend:").and_then(|n| n.parse::<u64>().ok()) {
        c.items.lock().unwrap().pending.retain(|p| p.id != n);
    }
    save(c, &mut c.items.lock().unwrap());
    Ok(json!({ "ok": true }))
}

fn http_id(id: &str) -> Option<u64> {
    id.strip_prefix("http:").and_then(|n| n.parse().ok())
}

// ---------------------------------------------------------------------------------------------
// Engine downloads
// ---------------------------------------------------------------------------------------------

/// Starts (or continues from its state file) the engine download of `it`.
fn start_http(c: &Core, it: &mut HttpItem) {
    let _in_runtime = c.rt.enter();
    let opts = Options::default();
    let resumed = it
        .file
        .as_ref()
        .map(|f| state_path(f))
        .filter(|p| p.exists())
        .and_then(|p| Download::resume(p, http::HeaderMap::new(), opts.clone()).ok());
    let dl = resumed.unwrap_or_else(|| {
        let url = url::Url::parse(&it.url).expect("the address was checked when it was added");
        Download::start(
            Request {
                url,
                headers: http::HeaderMap::new(),
                dest: Destination::Dir(it.dir.clone()),
            },
            opts,
        )
    });
    it.dl = Some(dl);
    it.status = "connecting";
}

/// Copies what the engine knows about a running download into the list's item.
fn refresh(it: &mut HttpItem) {
    let Some(dl) = &it.dl else {
        return;
    };
    let s = dl.snapshot();
    if s.file.is_some() {
        it.file = s.file.clone();
    }
    if s.size.is_some() {
        it.size = s.size;
    }
    it.downloaded = s.downloaded;
    it.speed = s.connections.iter().map(|c| c.speed).sum();
    it.status = match s.state {
        State::Probing => "connecting",
        State::Downloading | State::Verifying | State::Merging | State::Completing => {
            "downloading"
        }
        State::Done => "done",
        State::Failed => "error",
        State::Cancelled => "paused",
    };
    if matches!(s.state, State::Done | State::Failed | State::Cancelled) {
        it.error = s.error.clone();
        it.speed = 0;
        if s.state == State::Done
            && let Some(size) = it.size
        {
            it.downloaded = size;
        }
        it.dl = None;
    }
}

/// Writes the list when it changed (it is polled every second by the app).
fn save(c: &Core, items: &mut Items) {
    let saved = Saved {
        next: items.next,
        http: items
            .http
            .iter()
            .map(|i| SavedHttp {
                id: i.id,
                url: i.url.clone(),
                dir: i.dir.clone(),
                file: i.file.clone(),
                done: i.status == "done",
                size: i.size,
            })
            .collect(),
        names: items.names.clone(),
    };
    let Ok(text) = serde_json::to_string(&saved) else {
        return;
    };
    if text == items.saved {
        return;
    }
    let tmp = c.data.join("downloads.json.tmp");
    if std::fs::write(&tmp, &text).is_ok()
        && std::fs::rename(&tmp, c.data.join("downloads.json")).is_ok()
    {
        items.saved = text;
    }
}
