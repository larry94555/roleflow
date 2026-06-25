# RoleFlow: minimal Spring Boot prompt service backed by llama-server

## Summary

Initial version of **RoleFlow**, a minimal Spring Boot application that starts a local `llama-server`
(using the same launch behavior as goalmaker) and exposes a single REST endpoint for sending a prompt
and receiving the model's reply. A prompt can be supplied two ways: typed at the terminal, or entered on
a simple bundled web page.

The application is intentionally scoped to exactly three responsibilities — **no** tools, web search,
intermediary, or persistence:

1. Start and supervise `llama-server`.
2. Accept prompts typed at the terminal (stdin).
3. Serve a simple web page that submits prompts and shows the response.

## What's included

- **`LlamaServerManager`** — launches and supervises `llama-server`. Command construction, health-check
  polling, and the auto-restart watchdog are carried over unchanged from goalmaker (same flags, same
  defaults). Field initializers mirror the `@Value` defaults so the command builder is unit-testable.
- **`LlamaClient`** — calls llama-server's OpenAI-compatible `/v1/chat/completions` endpoint. Supports a
  **system prompt**, **user prompt**, optional **max tokens**, and optional **temperature**.
- **`AskController`** — `POST /ask` accepting `{ prompt, system?, maxTokens?, temperature? }` and
  returning `{ response }` (or `{ error }` with HTTP 400 for invalid input).
- **`TerminalPromptRunner`** — reads prompts from stdin on a daemon thread and prints replies; `exit`/
  `quit` stops the reader. Disabled automatically during tests.
- **`static/index.html`** — a dependency-free web page with system/prompt fields that posts to `/ask`.
- **Helper scripts** — `run.bat` to start the app, `ask.bat` to post a single prompt.
- **Maven wrapper** — `mvnw` / `mvnw.cmd` so the project builds with no system Maven installed.
- **`README.md`** — how to start the app, send prompts via terminal / web page / REST, and shut it down.

## REST API

`POST /ask`

```json
{ "prompt": "Name three primary colors.", "system": "Answer in one line.", "maxTokens": 64, "temperature": 0.2 }
```

→ `{ "response": "Red, blue, and yellow." }`

Only `prompt` is required; `system` falls back to the configured `roleflow.system-prompt`, and
`temperature` is omitted from the upstream request when not supplied.

## Testing

`mvnw test` — **23 tests, all passing**. The suite runs fully offline: test properties set
`llama.manage-server=false` and `roleflow.terminal.enabled=false`, so no `llama-server` process is
launched and stdin is never read.

| Test class                   | Coverage                                                                       |
|------------------------------|--------------------------------------------------------------------------------|
| `AskControllerTest`          | Forwarding, default system-prompt fallback, blank-prompt rejection, error body |
| `LlamaClientTest`            | Request-body shape + live round-trip against a stub HTTP server (incl. errors) |
| `TerminalPromptRunnerTest`   | Multi-prompt loop, `exit`, blank lines, error handling, disabled mode          |
| `LlamaServerManagerTest`     | Command construction: profiles, model path, ctx/port, extra args, draft model  |
| `RoleFlowApplicationTests`   | Spring context wiring                                                           |

## How to run

```bat
run.bat
```

Then either type prompts at the `RoleFlow>` terminal prompt, or open `http://localhost:8080/`.
Press **Ctrl+C** to stop (which also shuts down the managed `llama-server`).

## Notes / scope

- Defaults to the `small` profile (`Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M`); first run downloads the model.
- Requires Java 17+ and `llama-server` on `PATH` (or `llama.binary` set).
- No tools, web search, or storage by design — this is the minimal first pass.
