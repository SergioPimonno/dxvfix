# DXV Frame Doctor

A Resolume Arena user's tool to fix corrupted frames in video files.

Supports **DXV / DXV3**, **Apple ProRes**, **H.264/AVC** and **H.265/HEVC**. Fixes corrupted
frames either by duplicating a nearby good frame or by generating a new frame based on its
surroundings (motion-compensated interpolation). Requires a license file tied to the machine
it runs on.

Corrupted DXV3 frames are a common cause of Resolume Arena crashing mid-show. This tool scans
video files for structurally invalid frames — using logic ported from FFmpeg's own decoders so
it flags the same corruption a real decoder would choke on — and repairs them without needing
Resolume itself.

## Features

- **Codec support**: DXV / DXV3 (including the DXT1/DXT5/YCoCg texture variants), Apple ProRes
  (422, HQ, LT, Proxy, 4444, 4444 XQ), H.264/AVC, H.265/HEVC.
- **Two verification modes**:
  - *Fast* — structural bitstream validation (header/size checks, DXV opcode bounds-checking
    ported from FFmpeg, ProRes slice-table validation, H.264/H.265 NAL framing checks). No
    external dependencies.
  - *Deep* — actually decodes the file through ffmpeg and cross-checks which frames the decoder
    produced, catching corruption the fast structural check can't (at the cost of needing ffmpeg
    and running slower).
- **Two repair strategies**:
  - *Duplicate neighbor* — replace a bad frame with a verbatim copy of the nearest good frame.
    Always safe for intra-coded formats (DXV, ProRes); for H.264/H.265 prefers a guaranteed
    intra/IDR donor so it can't desync neighboring frames' decoding.
  - *Generate frame* — synthesize a new frame from the two nearest good frames via ffmpeg's
    motion-compensated interpolation, then re-encode it back into the source codec. Only applies
    to an isolated bad frame with good neighbors on both sides, and isn't available for DXV3's
    alpha-capable DXT5 texture format (ffmpeg has no encoder for it) — falls back to duplication
    automatically wherever it doesn't apply.
- **Batch queue**: drop in any number of files or whole folders (recursively, including nested
  subfolders); each file scans and repairs independently while you keep working with the rest of
  the queue.
- **Show monitoring mode**: point it at a live show-content folder and it keeps watching as files
  are added or removed, listing corrupted files as they show up. With auto-fix on, repaired
  copies are written into a dedicated subfolder inside the watched directory (which is itself
  never rescanned). Start/stop with one button so it costs nothing once a show's content is
  locked in — see [Load testing](#load-testing) below.
- **ffmpeg management**: auto-detects an existing install, or downloads and installs a Windows
  build with one click if none is found.
- **Signed, machine-locked licensing**: the app won't start without a license file matching the
  machine's hardware fingerprint. A standalone admin tool issues licenses for other machines
  without needing to ship the signing key with the app.
- **Language & theme**: interface available in Russian, English, German, French and Chinese
  (Mandarin) — switch it from the Settings menu (applies on next launch). Light/dark/system theme
  (via [FlatLaf](https://www.formdev.com/flatlaf/)) applies immediately.

## Requirements

- Windows (uses Windows-specific paths/process handling in a few places, e.g. `%LOCALAPPDATA%`).
- JDK 17 or newer to build/run (developed and tested on JDK 25). No other build tooling required
  — everything compiles with plain `javac`/`jar` via the provided PowerShell scripts.
- [ffmpeg](https://ffmpeg.org/) — optional, only needed for Deep verification and frame
  generation. The app can download it for you.

## Build

```powershell
.\build.ps1              # compiles and packages dxvfix.jar
java -jar dxvfix.jar      # run it
```

The license system needs its own keypair before the app can verify licenses — see
[Licensing](#licensing) below. `build.ps1` will warn if
`src/main/resources/dxvfix/license/public.key` is missing.

Theming depends on [FlatLaf](https://www.formdev.com/flatlaf/) (`lib/flatlaf-<version>.jar`,
downloaded once and committed to the repo since there's no dependency manager here). `build.ps1`
picks it up automatically and points `dxvfix.jar`'s manifest `Class-Path` at it, so when
distributing the built app, ship the `lib/` folder alongside `dxvfix.jar` — not just the jar by
itself.

## Usage

### Batch queue

Drag files or folders onto the queue panel (or use "Добавить файлы…"), pick a verification mode
and repair strategy, hit "Сканировать очередь", then "Исправить и сохранить как…" for any file
with bad frames. Multi-select works like a file explorer (Ctrl/Shift+click), and each row has its
own remove button.

### Show monitoring mode

Switch to the "Сопровождение шоу" tab, point it at your content folder, optionally enable
**"Автоисправление"**, and press "Начать сопровождение". The folder (and all subfolders) is
rescanned periodically; new or changed files are picked up automatically once they've finished
copying. Stop monitoring with the same button once the show is loaded in, to free up system
resources.

Full details of both modes, plus the difference between the verification modes and repair
strategies, are in the app's own Справка (Help) menu.

### Settings

The menu's "Настройки…" (Settings) dialog picks the interface language (Russian, English, German,
French, Chinese) and color theme (light/dark/system). Theme changes apply immediately across every
open window; a language change takes effect the next time the app is launched.

## Licensing

License files are ECDSA-signed and tied to a machine fingerprint (derived from its MAC address),
verified against a public key embedded in the app — so a valid license can only be produced by
whoever holds the private signing key, not just anyone who has the app jar.

1. Generate a keypair once (keep `license_private.key` secret, never commit or distribute it):
   ```powershell
   java -cp dxvfix.jar dxvfix.license.tools.GenerateKeyPairMain <output-dir>
   ```
   Copy the resulting public key into `src/main/resources/dxvfix/license/public.key` before
   building the app you intend to distribute.
2. Issue a license for a given machine (it'll show you its fingerprint on first run) using the
   standalone admin tool — a separate jar so end users never need the private key anywhere near
   their machine:
   ```powershell
   .\build-keygen.ps1                      # builds dxvfix-license-admin.jar
   java -jar dxvfix-license-admin.jar       # GUI: pick the private key, enter/paste a fingerprint, issue
   ```
   A CLI equivalent (`dxvfix.license.tools.GenerateLicenseMain`) is also available for scripting.
   `.\build-keygen-exe.ps1` additionally wraps the admin tool into a native, self-contained
   `.exe` (via `jpackage`) for handing to someone without a JVM installed.

## Load testing

The show monitoring mode was load-tested to make sure it doesn't compete with Resolume Arena for
system resources on the same machine: with the recommended fast-scan + duplicate-repair
configuration, CPU use was negligible (dominated by disk I/O, not computation). Even the heaviest
path (deep scan + frame generation, which spawns ffmpeg subprocesses) only produces a brief,
few-second burst of load per newly-detected corrupted file, and files are always processed one at
a time — never in parallel — specifically to avoid contending with a real-time rendering app like
Arena.

## Project structure

```
src/main/java/dxvfix/
  dxv/        DXV/DXV3 bitstream structural validator (ported from FFmpeg's dxv.c)
  prores/     ProRes frame/slice-table structural validator
  h26x/       H.264/H.265 NAL framing validator
  mp4/        MOV/MP4 box parser (sample tables, codec/track info)
  scan/       Orchestrates validators across a file's samples
  repair/     Rewrites the MOV/MP4 container with repaired samples
  generate/   ffmpeg-based frame interpolation + re-encode
  ffmpeg/     Locating, installing, and deep-decode-validating via ffmpeg
  queue/      Batch queue data model
  watch/      Show monitoring engine
  license/    Signed license records, verification, machine fingerprinting
  i18n/       UI string lookup (messages_<lang>.properties)
  settings/   Persisted language/theme preference
  theme/      FlatLaf light/dark/system theme application
  gui/        Swing UI
```

## License

MIT — see [LICENSE](LICENSE).
