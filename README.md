# CardLife Launcher

> **Version 1.0 – first public release.**
> It works, but it is an early version: expect rough edges, and feel free to open an issue or a pull request.

A local emulator of the CardLife authentication API, bundled with a GUI launcher. It finds your game, makes backups, redirects the game's server addresses to `127.0.0.1`, and starts the server and the game.

*Unofficial project. Not affiliated with or endorsed by Freejam. It contains no game files – you need your own copy of the game.*

## Quick start (Windows)

1. Run **`start.bat`**. You don't need to install anything else – the script will:
   * find JDK 17+ (`JAVA_HOME`, `PATH`, Program Files, registry) or **download a portable Temurin 17** into `.tools\jdk-17`;
   * download the **FlatLaf** library into `lib\` (optional – without it the window uses the default Swing look);
   * compile the project with `javac` (**Maven is not required**) and open the launcher window.
2. In the window, pick a language, choose the game folder (or click "Find game"), then press **"Server only"** or **"Server + game"**.

Linux / macOS: `./start.sh` (GUI) or `./start.sh --cli --server-only --port 8080` (console).

## Reliable downloads

* **TLS 1.2/1.3** is forced (older Windows PowerShell defaults to TLS 1.0, which most sites reject);
* every link gets **3 attempts**, then the next **mirror** (JDK: Adoptium API → direct Adoptium link → Microsoft OpenJDK → Amazon Corretto; FlatLaf: Maven Central → Apache mirror → Google → Aliyun);
* three transfer methods: `Invoke-WebRequest` → `curl.exe` → BITS;
* **size**, **SHA-256** (JDK) / **SHA-1** (FlatLaf) and ZIP integrity are verified (the JDK archive must contain `javac.exe`);
* an already installed or already downloaded JDK is never downloaded again, and later launches work offline.

If a download fails, the script shows the reason. You can also install JDK 17 manually: https://adoptium.net/temurin/releases/?version=17

## What's in the window

* a status indicator (stopped / running on port / game started), uptime and request counter;
* a colored **Log** tab (clear / copy / save, auto-scroll) and a **Requests** tab – a live table of all requests sent by the game; click a row to see the request body;
* **port** selection (for "Server + game" it is always 8080, because that address is written into the patch);
* a **Tools** menu: status page in the browser, the `responses` / `overrides` / game folders, URL scan inside the DLL, patch rollback;
* languages: Russian, English, Ukrainian; dark / light theme; settings are stored in `launcher.properties`;
* the log is also written to `launcher.log` (rotated at 2 MB); application errors go to `launcher-error.log`.

## Server

* answers `200` + JSON on any path and any method, and saves every request to `responses/` (the last 500 are kept);
* custom responses: put a file in `overrides/` – `api_auth_login.json` or `POST_api_auth_login.json` (a file with the method has priority); it is re-read on every request;
* CORS and `OPTIONS` preflight support, plus a crash guard (the handler returns 500 instead of leaving the connection hanging);
* service endpoints: `/_status` (HTML), `/_health`, `/_requests` (JSON).

See `overrides/README.txt` for the exact file naming rules.

## Console mode

`java -jar cardlife-launcher.jar --help` – supports `--server-only`, `--scan`, `--revert`, `--no-patch`, `--port` and more.

## Status and known limitations

* This is the first release. The game starts and talks to the local server, but most endpoints currently return generic placeholder JSON.
* Realistic responses for specific endpoints (auth, client, status, ...) still have to be filled in `overrides/`. Contributions are welcome.
* Patching is done in place on the game's `Assembly-CSharp.dll`: only URL strings that are not longer than the original are replaced; strings that are too short are skipped.

## Important

This is a local API emulator for testing and compatibility. Do not use it to access other people's accounts or to bypass protections of third-party services.

Before launching the game, the patcher creates `.bak` copies of `Assembly-CSharp.dll` and `ServerConfig.json`. You can restore the originals via **Tools → Revert game patch**.

If Steam is installed in `Program Files`, writing to the game folder may require running the launcher as administrator.

## License

Released under the [MIT License](LICENSE). FlatLaf is licensed under Apache-2.0.
