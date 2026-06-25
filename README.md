# RoleFlow

A minimal Spring Boot application that launches a local [`llama-server`](https://github.com/ggml-org/llama.cpp)
and exposes a single REST endpoint, `POST /ask`, for sending a prompt and getting the model's reply.

There are two ways to send a prompt:

1. **From the terminal** — the running application reads prompts from standard input and prints the reply.
2. **From a web page** — open `http://localhost:8080/` in a browser, type a prompt, and see the response.

The application does exactly three things and nothing more:

- starts and supervises `llama-server` (same launch behavior as goalmaker),
- accepts prompts typed at the terminal,
- serves a simple web page that submits prompts and shows the response.

---

## Requirements

- **Java 17 or newer** on your `PATH`.
- **`llama-server`** (from llama.cpp) on your `PATH`, or set `llama.binary` to its full path (see
  [Configuration](#configuration)). On the first run, `llama-server` downloads the model
  (`Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M` by default), which can take a few minutes.
- Maven is **not** required — the bundled wrapper (`mvnw` / `mvnw.cmd`) uses a system Maven if present
  and otherwise downloads a pinned copy automatically.

---

## How to start the application

From the project directory (`c:\Users\larry\github\RoleFlow`):

**Windows (recommended):**

```bat
run.bat
```

**Any platform, using the Maven wrapper directly:**

```bash
./mvnw spring-boot:run        # macOS / Linux / Git Bash
mvnw.cmd spring-boot:run      # Windows
```

On startup the app launches `llama-server` and waits until it reports healthy. The first launch can be
slow while the model downloads — progress is written to `llama-server.log` in the project directory.
Once you see `RoleFlow terminal ready.` and Spring's `Started RoleFlowApplication`, it is ready.

The web server listens on **port 8080**; `llama-server` listens on **port 8081**.

---

## Option 1 — Send a prompt from the terminal

While the application is running, the terminal where you started it shows a prompt:

```
RoleFlow terminal ready. Type a prompt and press Enter (type 'exit' or 'quit' to stop).
RoleFlow> What is the capital of France?
The capital of France is Paris.
RoleFlow>
```

Type your prompt, press **Enter**, and the reply is printed. Keep entering prompts as many times as
you like. Typing `exit` or `quit` stops the terminal reader (the application keeps running so the web
page and REST endpoint stay available).

### Sending a one-off prompt with `ask.bat`

If you prefer not to use the interactive prompt, `ask.bat` posts a single prompt to the REST endpoint
from any terminal while the app is running:

```bat
ask.bat "Write a haiku about the sea"
ask.bat "Translate to French: good morning" "You are a professional translator."
```

The first argument is the user prompt; the optional second argument is a system prompt.

---

## Option 2 — Send a prompt from the web page

While the application is running, open:

```
http://localhost:8080/
```

Enter an optional **system prompt**, type your **prompt**, and click **Send** (or press **Ctrl+Enter**).
The model's response appears below the button.

---

## The REST API

Both the terminal and the web page are thin clients over one endpoint.

### `POST /ask`

Request body (JSON):

| Field         | Type    | Required | Description                                                        |
|---------------|---------|----------|--------------------------------------------------------------------|
| `prompt`      | string  | yes      | The user prompt.                                                   |
| `system`      | string  | no       | System prompt. Falls back to `roleflow.system-prompt` if omitted.  |
| `maxTokens`   | integer | no       | Max tokens to generate. Falls back to `prompt.max-tokens`.         |
| `temperature` | number  | no       | Sampling temperature. Omitted from the request when not provided.  |

Response body (JSON): `{ "response": "..." }`, or `{ "error": "..." }` with HTTP 400 for a bad request.

Example with `curl`:

```bash
curl -s http://localhost:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Name three primary colors.","system":"Answer in one line."}'
```

---

## How to end the application

- Press **Ctrl+C** in the terminal where the application is running. Spring Boot shuts down and stops the
  managed `llama-server` process automatically.
- If you started it in the background, stop the Java process for `RoleFlowApplication` (for example, via
  Task Manager on Windows or `pkill -f RoleFlowApplication` on macOS/Linux).

(Typing `exit` or `quit` at the `RoleFlow>` prompt only stops reading terminal input — it does **not**
shut down the application. Use **Ctrl+C** to fully stop it.)

---

## Configuration

Settings live in [`src/main/resources/application.properties`](src/main/resources/application.properties)
and can be overridden on the command line, e.g. `--server.port=9000`. The most useful options:

| Property                  | Default                | Description                                              |
|---------------------------|------------------------|----------------------------------------------------------|
| `server.port`             | `8080`                 | Port for the web page and REST API.                      |
| `roleflow.system-prompt`  | *(empty)*              | Default system prompt for terminal/REST calls.           |
| `roleflow.terminal.enabled` | `true`               | Read prompts from stdin. Set `false` to disable.         |
| `prompt.max-tokens`       | `1024`                 | Default response length cap.                             |
| `llama.binary`            | *(empty → on PATH)*    | Full path to `llama-server` if not on `PATH`.            |
| `llama.profile`           | `small`                | `small` / `medium` / `large` model presets.             |
| `llama.model-path`        | *(empty)*              | Use a local `.gguf` file instead of a profile download.  |
| `llama.port`              | `8081`                 | Port `llama-server` listens on.                          |
| `llama.manage-server`     | `true`                 | Set `false` to use an externally started `llama-server`. |

---

## Running the tests

```bash
./mvnw test          # macOS / Linux / Git Bash
mvnw.cmd test        # Windows
```

The test suite never launches `llama-server` or reads from stdin (those are disabled in
`src/test/resources/application.properties`), so it runs fast and offline.

---

## Project layout

```
src/main/java/com/example/roleflow/
  RoleFlowApplication.java     Spring Boot entry point
  LlamaServerManager.java      Launches and supervises llama-server
  LlamaClient.java             Calls llama-server's /v1/chat/completions
  AskController.java           POST /ask REST endpoint
  TerminalPromptRunner.java    Reads prompts from the terminal (stdin)
src/main/resources/
  application.properties       Configuration
  static/index.html            The web page
src/test/java/com/example/roleflow/
  ...Test.java                 Unit and context tests
```
