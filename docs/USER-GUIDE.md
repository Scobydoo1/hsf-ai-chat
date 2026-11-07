# User Guide: AI Chat App (external AI chat · RAG · switchable providers)

**Back-end:** Spring Boot 3.5 (Java 21) REST API + Spring Data JPA · **DB:** SQL Server (local) / Neon PostgreSQL (cloud)
**Front-end:** HTML + Bootstrap 5 + plain JavaScript (no build) · **Hosting:** Render (API) + Vercel (front-end)

The app covers the three requirements of the brief:

| Requirement | Where in the app | Where in the code |
|-------------|------------------|-------------------|
| Chat with an external AI | **New chat → Chat with AI** | `ChatService.send`, `ai/*Provider.java` |
| Chat with AI using RAG | **Documents** tab + **New chat → Chat with my documents** | `DocumentService`, `RetrievalService`, `ChatService` |
| Configure API keys to switch the chat platform | **Settings** tab and the **AI:** box at the top right | `ProviderService`, `ProviderConfig`, `ApiKeyConverter` |

Hosting step by step: **[../../DEPLOY-GUIDE.md](../../DEPLOY-GUIDE.md)**. Test questions: **[TEST-QUESTIONS.md](TEST-QUESTIONS.md)**.

---

## 1. Architecture

```
 Browser (frontend/, Vercel)                     Spring Boot API (backend/, Render)                 AI platforms
 ┌──────────────────────────┐   fetch + JSON    ┌───────────────────────────────────┐   HTTPS      ┌──────────────┐
 │ index.html  Bootstrap    │ ────────────────► │ Controllers  /api/...             │ ───────────► │ Gemini       │
 │ js/app.js   UI logic     │   X-App-Password  │ Services     Chat, RAG, Providers │              │ OpenAI, Groq │
 │ js/api.js   fetch()      │ ◄──────────────── │ ai/ChatProvider (one interface)   │ ◄─────────── │ Ollama       │
 │ js/config.js API URL     │                   │ JPA repositories                  │              │ Claude (SDK) │
 └──────────────────────────┘                   └──────────────┬────────────────────┘              └──────────────┘
                                                                │ JDBC
                                                    SQL Server (local) / Neon PostgreSQL (cloud)
```

### Database tables (created by JPA)

| Table | Entity | Holds |
|-------|--------|-------|
| `ai_providers` | `ProviderConfig` | platform type, base URL, chat model, embedding model, **encrypted** API key, active flag |
| `conversations` | `Conversation` | title, mode (`CHAT` or `RAG`) |
| `chat_messages` | `ChatMessage` | role, text, which provider/model answered, RAG sources (JSON) |
| `documents` | `KnowledgeDocument` | uploaded file name, size, embedding model used |
| `document_chunks` | `DocumentChunk` | ~1000-character pieces of each document + embedding vector (bytes) |

---

## 2. Run it locally

### 2.1 Requirements
JDK 21 · IntelliJ IDEA (Community is fine) · SQL Server · a browser. No Maven or Node install needed.

### 2.2 Back-end in IntelliJ
1. Run `backend/database/create-database.sql` once in SSMS (creates `AiChatDB`).
2. **File → Open →** select the **`small-project/backend`** folder → Trust Project → wait for Maven import.
3. Project SDK = 21 (*File → Project Structure*).
4. Choose the run configuration **AiChatApplication (local SQL Server)** (top right).
   If your `sa` password is not `12345`: *Run → Edit Configurations → Environment variables* → `DB_PASSWORD=yourPassword`.
5. ▶ Run. Check <http://localhost:8080/api/status>; it should show `{"status":"ok", ...}`.

On first start the app creates the **Demo (offline, no key)** provider and loads the **4 sample documents**,
so you can test everything before you have any API key.

### 2.3 Front-end
The front-end is static files. Serve them from any local web server (opening the file directly as `file://` is
blocked by the browser's CORS rules):

* **IntelliJ:** open `small-project/frontend/index.html` → hover the top right of the editor → click a browser icon
  (IntelliJ serves it at `http://localhost:63342/...`), **or**
* **Terminal:** `cd small-project/frontend` → `python -m http.server 5500` → open <http://localhost:5500>, **or**
* **VS Code:** the *Live Server* extension → *Go Live*.

`js/config.js` sends requests to `http://localhost:8080` whenever the page is opened from `localhost`.

### 2.4 Get an API key (pick at least one)

| Platform | Free? | Get a key | Chat model (preset) | Embeddings |
|----------|-------|-----------|---------------------|------------|
| Google Gemini | Free tier | <https://aistudio.google.com/apikey> | `gemini-3.8-flash` | `gemini-embedding-001` ✅ |
| Groq | Free tier | <https://console.groq.com/keys> | `llama-3.3-70b-versatile` | ❌ |
| OpenAI | Paid | <https://platform.openai.com/api-keys> | `gpt-5-mini` | `text-embedding-3-small` ✅ |
| Anthropic Claude | Paid | <https://console.anthropic.com/settings/keys> | `claude-opus-5-5` | ❌ |
| Ollama | Free, runs on your PC | <https://ollama.com/download> | `llama3.2` | `nomic-embed-text` ✅ |

Model names change over time. If a test says "check the model name", look up the current name on the platform's
model page and edit it in Settings.

**Recommended for students:** Gemini for chat **and** embeddings (both free), plus Groq as a second chat platform to
show switching.

### 2.5 Add the key in the app
**Settings** tab → *Start from a preset* → pick the platform → paste the key → keep **Use for embeddings** ticked for
Gemini → **Save** → **Test**. Then click **Use for chat**, or pick it in the **AI:** box at the top right.

After changing the embedding platform, open **Documents → Re-index all**, so the sample documents get vectors from the
new model.

Alternative: put keys in environment variables (`GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`GROQ_API_KEY`). `ProviderSeeder` creates those providers on start-up.

### 2.6 Tests
`cd backend` → `mvnw.cmd test` (Windows) or `./mvnw test`. The tests use an in-memory H2 database and the Demo
provider, so they need no SQL Server and no API key. They cover chat, RAG with sources, key encryption and masking,
switching providers, the app password, CORS and the sample data.

---

## 3. How the code works

### 3.1 One interface, many platforms (switching the chat platform)

```java
public interface ChatProvider {
    ProviderType type();
    String chat(ProviderConfig config, String systemPrompt, List<AiMessage> messages);
    default boolean supportsEmbeddings() { return false; }
    default List<float[]> embed(ProviderConfig config, List<String> texts) { ... }
}
```

| Implementation | Talks to | How |
|----------------|----------|-----|
| `GeminiProvider` | Google Gemini | `RestClient` → `POST /models/{model}:generateContent` (+ `:batchEmbedContents`), header `x-goog-api-key` |
| `OpenAiCompatibleProvider` | OpenAI, Groq, Ollama, LM Studio, DeepSeek… | `RestClient` → `POST {baseUrl}/chat/completions` (+ `/embeddings`), `Authorization: Bearer` |
| `AnthropicProvider` | Claude | official **Anthropic Java SDK** (`com.anthropic:anthropic-java`) |
| `DemoProvider` | nothing (offline) | echoes the question; "feature hashing" embeddings |

`ProviderRegistry` maps a `ProviderType` to its implementation. `ChatService` only does:

```java
ProviderConfig provider = providerService.active().orElseThrow(...);       // the platform picked in Settings
String answer = providerService.implementation(provider).chat(provider, systemPrompt, history);
```

So **switching platforms is just changing which `ProviderConfig` row is active**: `POST /api/providers/{id}/activate`.
No code changes and no restart.

**API key safety**
* Keys are encrypted with AES-GCM before they are saved (`ApiKeyConverter`, a JPA `AttributeConverter`), using the
  `APP_SECRET_KEY` environment variable.
* The API never returns a full key, only `sk-pr••••abcd` (`ProviderService.mask`).
* Leaving the key field empty when editing keeps the saved key.
* `APP_PASSWORD` protects every `/api` call (header `X-App-Password`), so strangers cannot spend your credits.

### 3.2 RAG step by step

```
UPLOAD   file ──► extract text (PDFBox for PDF) ──► TextChunker (1000 chars, 150 overlap)
              ──► embed each chunk (embedding provider) ──► save chunks + vectors (document_chunks)

ASK      question ──► embed question ──► cosine similarity with every chunk ──► top 4 chunks
              ──► system prompt = rules + "[1] (file) text … [2] …" ──► chat provider ──► answer with [1] [2]
```

* **Chunking**: `TextChunker.split` cuts at paragraph, sentence or space boundaries near 1000 characters, and the
  pieces overlap by 150 so a sentence on the border is still found.
* **Vectors in a normal database**: a vector is stored as `byte[]` (float32), and `RetrievalService` computes cosine
  similarity in Java. This works the same on SQL Server and PostgreSQL. (For millions of chunks you would use a vector
  database or Neon's `pgvector`; for a course project in-memory search is fast enough.)
* **Same model rule**: vectors from different embedding models cannot be compared. Each chunk stores the model it was
  embedded with (e.g. `GEMINI:gemini-embedding-001`), and search only uses chunks of the current embedding model.
* **Fallback**: if no embedding provider is set (or a document was embedded with another model), `RetrievalService`
  uses keyword search (TF-IDF), so RAG still works, just less smart.
* **Prompt** (`ChatService.RAG_PROMPT`): answer only from the context, cite `[n]`, and say so when the answer is not in
  the documents. The sources are saved with the answer and shown under it.

### 3.3 REST API

| Method | URL | Purpose |
|--------|-----|---------|
| GET | `/api/status` | Health and active provider (public, no password) |
| GET / POST | `/api/providers` | List / add a platform |
| PUT / DELETE | `/api/providers/{id}` | Edit (empty key = keep) / delete |
| POST | `/api/providers/{id}/activate` | **Switch the chat platform** |
| POST | `/api/providers/{id}/test` | Send a test prompt |
| GET / POST | `/api/conversations` | List / create (`{"mode":"CHAT"}` or `{"mode":"RAG"}`) |
| GET / POST | `/api/conversations/{id}/messages` | History / send a message and get the answer |
| DELETE | `/api/conversations/{id}` | Delete a conversation |
| GET / POST | `/api/documents` | List / upload a file (multipart `file`) |
| POST | `/api/documents/text` | Add pasted text |
| POST | `/api/documents/reindex` | Re-embed everything with the current embedding model |
| DELETE | `/api/documents/{id}` | Delete a document |

Errors always come back as `{"error": "message"}` (`ApiExceptionHandler`), and the front-end shows them in red.

### 3.4 Front-end

| File | Job |
|------|-----|
| `index.html` | Three Bootstrap tabs (Chat, Documents, Settings), password modal, toast |
| `js/config.js` | Back-end URL: localhost:8080 locally, your Render URL online |
| `js/api.js` | `fetch` wrapper: JSON, the `X-App-Password` header, error messages |
| `js/app.js` | UI logic; answers are rendered with `marked` (Markdown) and cleaned with `DOMPurify` |

---

## 4. Configuration reference (environment variables)

| Variable | Local default | On Render |
|----------|---------------|-----------|
| `SPRING_PROFILES_ACTIVE` | (none = SQL Server) | `prod` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | SQL Server `AiChatDB`, `sa`, `12345` | Neon JDBC URL, user, password |
| `APP_PASSWORD` | empty = no password | **set it** |
| `APP_SECRET_KEY` | built-in dev value | **set a long random string, and never change it** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | `https://your-app.vercel.app` |
| `APP_SAMPLE_DATA` | `true` | `true` or `false` |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GROQ_API_KEY` | optional | optional |
| `PORT` | 8080 | set by Render |

---

## 5. Troubleshooting

| Problem | Fix |
|---------|-----|
| Front-end says *Cannot reach the back-end* | Is the back-end running? Is the URL in `js/config.js` right? On Render's free plan wait about a minute for it to wake up. |
| Browser console: *blocked by CORS policy* | Add the front-end's exact origin to `CORS_ALLOWED_ORIGINS` (no trailing slash) and restart. Don't open the page as `file://`. |
| `HTTP 401 (check the API key)` | Wrong or expired key: edit the provider, paste it again, then **Test**. |
| `HTTP 404 (check the model name…)` | The model name is wrong or retired: check the platform's model list and edit it. |
| `HTTP 429` | Free-tier rate limit: wait a minute, or switch platform. |
| RAG answers ignore the documents | The Documents tab shows **keyword** instead of **vector**: tick *Use for embeddings* on Gemini/OpenAI/Ollama, then **Re-index all**. |
| "Could not decrypt a saved API key" in the log | `APP_SECRET_KEY` changed: enter the keys again in Settings. |
| Ollama works locally but not on Render | Render cannot reach your PC. Use Ollama only with a local back-end. |
| SQL Server connection errors | See the troubleshooting table in `assignment/docs/USER-GUIDE.md`; the same fixes apply (TCP/IP, port 1433, `sa` login). |
