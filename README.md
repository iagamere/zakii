# PS4 Download Monitor 4.2

Open in Android Studio (JDK 17), sync Gradle, run. NOT compiled by the author (no Android SDK available): expect to fix a few compile errors on first sync.

How it works (all verified from the ezRemote sources):
- Send: POST /__local__/download_url on ezRemote Client (:8080) with dest = FINAL FILE PATH (folder + unique name).
- Monitor: ezRemote Server (:6701) GET /get_download_state (live bytes/size/state); when it is down, bg_download_history.json.
- Pause / Resume / Delete, two modes (detected automatically with GET /v2/info on :6701):
  * CUSTOM server (ps4-ezremote-server-abdo, API /v2): POST /v2/pause, /v2/resume, /v2/delete {id,...} act on ONE download immediately;
    the other downloads keep running and nothing has to be restarted. Live list from GET /v2/downloads (server-measured speed, connection count,
    error text). A failed / "needs link" download is continued by sending the link again to the SAME file path (the Retry button does that).
  * ORIGINAL server: stop ezRemote Server, edit bg_download_history.json (state 3 + failed_attempts 5 / 1, or remove the entry), delete files.
    The server reads the file only at start-up, so a running server cannot be told to stop one download.
- Files: browse, rename, new folder, copy/cut/paste, extract, install, delete (ezRemote web only; no FTP).
- PKG reader: title, icon, header, entries (partial reads over ezRemote downloadFile with Range).

Files: Models, Store, EzRemote (client API), EzServer (+ BgHistory), DownloadMonitor, Ops, Pkg, Notifier, MonitorService,
Names, Fmt, Lang, and the UI (MainActivity, Ui, HomeUi, DownloadsUi, FilesUi, PkgUi, BrowserUi, SettingsUi).
Removed in 4.0: FTP, file-matching heuristics, Net size check, install-from-link, upload, text editor, save-to-phone, image preview,
artwork scan, history screen, auto-rename/auto-install, adoption toggle, "stop monitoring", speed chart, untracked .tmp list, events list.


## 4.2 (custom server edition)
- **Server screen** (Settings > ezRemote Server): status (custom / original / unreachable), choose the server .elf once and send it to GoldHEN's BinLoader
  (TCP 9090) with one tap, server download settings (parallel downloads, automatic / fixed connections), server log (also read from the PS4 file
  when the server is down).
- **Self-healing:** expired links are refreshed automatically (the original link is sent again to the SAME file, up to 5 times in a row); if the
  server stops answering for 60 s while downloads are unfinished, the saved .elf is sent again (at most every 5 minutes).
- **Notifications:** one ongoing notification listing every active download (overall progress, speed, ETA), tinted with the game's cover colour;
  a heads-up alert when a download is interrupted (server not reachable 90 s / no progress 3 min; one per incident, removed when it recovers;
  setting "Alert me when a download is interrupted"); one grouped notification per finished download.
- **Cover colours:** cards, progress bars and the detail screen take the dominant colour of the game's cover.
- The custom server retries transient failures forever (only consecutive failures count), so downloads no longer stop and wait for "Resume".
