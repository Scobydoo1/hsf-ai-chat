# User Guide: AI Chat App (one chat · answers from your documents · switchable AI platforms)

**Back-end:** Spring Boot 3.5 (Java 21) REST API + Spring Data JPA · **DB:** H2 file (local, no setup) / SQL Server (local) / Neon PostgreSQL (cloud)
**Front-end:** HTML + Bootstrap 5 + plain JavaScript (no build) · **Hosting:** Render (API) + Vercel (front-end), all on free plans

The app covers the three requirements of the brief:

| Requirement | Where in the app | Where in the code |
|-------------|------------------|-------------------|
| Chat with an external AI | **Chat** tab. In the chat box pick **Don't use documents** for a plain chat (fastest) | `ChatService.send`, `ai/*Provider.java` |
| Chat with AI using RAG | **Documents** tab (upload or paste) + the document picker in the chat box: **All documents** or **Choose documents…**. Answers cite their passages like `[1]` | `DocumentService`, `RetrievalService`, `ChatService` |
| Configure API keys to switch the chat platform | **Settings** tab, and the AI menu at the top right (**✦ Google Gemini ▾**) | `ProviderService`, `ProviderConfig`, `ApiKeyConverter` |

What version 2 added (the group's extra requests):

| Request | What the app does | Where in the code |
|---------|-------------------|-------------------|
| "Combine the 2 chats into 1" | One kind of chat. Every question decides whether to use documents, and which ones | `SendMessageRequest.useDocuments / documentIds`, `frontend/js/chat.js` |
| "Choose which documents to search" | Document picker in the chat box: *Don't use documents* · *All documents (N)* · *Choose documents…* (tick the files). **Ask about this** on a document card searches only that file | `RetrievalService.search(question, documentIds)` |
| "Change AI when Gemini or Groq hits its limit" | If the platform you chose hits its free-tier limit (HTTP 429) or stops responding, the next working platform answers and a pink note under the answer says so. If every platform fails, the error offers **Retry** and **Ask &lt;platform&gt;** buttons. The automatic part can be switched off | `ProviderService.fallbackChain`, `ProviderHealth`, `ChatService` |
| "Neon, Render and Vercel are free, so a little slow" | "Waking up the free server…" banner, a seconds counter while the AI thinks, a keep-awake ping, few requests, a light page | `frontend/js/app.js`, `StatusSnapshot`, section 3.4 |
| "Make the front-end a little more fancy" | "Study desk with highlighters": citations are yellow highlighter chips that open the matching source card; light, dark and auto theme | `frontend/css/style.css`, `frontend/js/messages.js` |

Test questions: **[TEST-QUESTIONS.md](TEST-QUESTIONS.md)**. Hosting: the short version is in section 4.1; the full
click-by-click tutorial is `DEPLOY-GUIDE.md` in the course folder next to `small-project/` (it is not in this repository).

---

## 1. Architecture

```
 Browser (frontend/, Vercel)                     Spring Boot API (backend/, Render)                 AI platforms
 ┌──────────────────────────┐   fetch + JSON    ┌───────────────────────────────────┐   HTTPS      ┌──────────────┐
 │ index.html  Bootstrap    │ ────────────────► │ Controllers  /api/...             │ ───────────► │ Gemini       │
 │ css/style.css            │   X-App-Password  │ Services     Chat, RAG, Providers │              │ OpenAI, Groq │
 │ js/*.js  8 small scripts │ ◄──────────────── │ ai/ChatProvider (one interface)   │ ◄─────────── │ Ollama       │
 │ js/config.js API URL     │                   │ JPA repositories                  │              │ Claude (SDK) │
 └──────────────────────────┘                   └──────────────┬────────────────────┘              └──────────────┘
                                                                │ JDBC
                                     H2 file or SQL Server (local) / Neon PostgreSQL (cloud)
```

### Database tables (created by JPA)

| Table | Entity | Holds |
|-------|--------|-------|
| `ai_providers` | `ProviderConfig` | platform type, base URL, chat model, embedding model, **encrypted** API key, active flag |
| `conversations` | `Conversation` | title, mode (`CHAT` or `RAG`; only kept for chats made before version 2) |
| `chat_messages` | `ChatMessage` | role, text, which provider/model answered, sources (JSON), notice, duration, number of documents searched |
| `documents` | `KnowledgeDocument` | uploaded file name, size, embedding model used |
| `document_chunks` | `DocumentChunk` | ~1000-character pieces of each document + embedding vector (bytes) |

---

## 2. Run it locally (any computer with IntelliJ)

### 2.1 Requirements
* **JDK 21 or newer.** No JDK yet? IntelliJ can download one: *File → Project Structure → SDK → Download JDK*.
* **IntelliJ IDEA**, Community or Ultimate, on Windows, macOS or Linux.
* A browser.

That is all for the H2 option: **no Maven, Node or SQL Server to install** (IntelliJ brings Maven, the Maven wrapper
`mvnw` is in the repository, and H2 is a Java library).

### 2.2 Open the project
1. Clone <https://github.com/Scobydoo1/hsf-ai-chat> (IntelliJ: *File → New → Project from Version Control*).
2. **File → Open →** the cloned folder, the one that contains `pom.xml`, `backend/`, `frontend/` and `docs/`
   → **Trust Project**. IntelliJ imports the Maven project by itself; the first time it downloads the libraries
   (a few minutes).
3. Check *File → Project Structure → Project → SDK* = 21 (or newer).

The root `pom.xml` only lists `backend` as a module, so IntelliJ shows the front-end and the docs next to the Java code.
Render and Vercel do not use it.

### 2.3 Start the back-end
Pick a run configuration in the top-right corner of IntelliJ and press ▶:

| Run configuration | Database | Setup |
|-------------------|----------|-------|
| **AiChatApplication (H2, no setup)** (recommended) | H2 file `backend/data/aichat.mv.db` | none. The data survives a restart; delete `backend/data/` to start fresh |
| **AiChatApplication (local SQL Server)** | SQL Server `AiChatDB` | run `backend/database/create-database.sql` once in SSMS. If your `sa` password is not `12345`: *Run → Edit Configurations → Environment variables* → `DB_PASSWORD=yourPassword` |

The H2 configuration only sets `SPRING_PROFILES_ACTIVE=h2` (file `application-h2.properties`) and uses `backend/` as
the working directory. It is ready when the log says `Started AiChatApplication`; <http://localhost:8080/api/status>
then shows `{"status":"ok", ...}`. With H2 you can look at the tables at <http://localhost:8080/h2-console>
(JDBC URL `jdbc:h2:file:./data/aichat`, user `sa`, empty password; only reachable from your own computer).

On first start the app creates the **Demo (offline, no key)** provider and loads the **4 sample documents**, so you can
test everything before you have any API key.

### 2.4 Front-end
Open `frontend/index.html` in IntelliJ, then click one of the browser icons in the top-right corner of the editor
(or right-click the file → **Open In → Browser**). IntelliJ serves the page at `http://localhost:63342/...`, which the
back-end already allows (CORS). **Do not double-click the file**: a `file://` page cannot call the API.

Other ways to serve the folder: `cd frontend` → `python -m http.server 5500` → <http://localhost:5500>, or the
*Live Server* extension of VS Code. Whenever the page is opened from `localhost`, `js/config.js` sends the requests to
`http://localhost:8080`.

### 2.5 Get an API key (pick at least one)

| Platform | Free? | Get a key | Chat model (preset) | Embeddings |
|----------|-------|-----------|---------------------|------------|
| Google Gemini | Free tier | <https://aistudio.google.com/apikey> | `gemini-3.8-flash` | `gemini-embedding-001` ✅ |
| Groq | Free tier | <https://console.groq.com/keys> | `qwen/qwen3.8-27b` | ❌ |
| OpenAI | Paid | <https://platform.openai.com/api-keys> | `gpt-5-mini` | `text-embedding-3-small` ✅ |
| Anthropic Claude | Paid | <https://console.anthropic.com/settings/keys> | `claude-opus-5-5` | ❌ |
| Ollama | Free, runs on your PC | <https://ollama.com/download> | `llama3.2` | `nomic-embed-text` ✅ |

Model names change over time. If a test says "check the model name", look up the current name on the platform's
model page and edit it in Settings.

**Recommended for students:** Gemini for chat **and** embeddings (both free), plus **Groq** as a second chat platform.
With two real platforms, automatic switching has somewhere to go when one of them hits its free-tier limit.

### 2.6 Add the key in the app
**Settings** tab → *Start from a preset* → pick the platform → paste the key → keep **Use for embeddings** ticked for
Gemini → **Save** → **Test**. Then click **Use for chat**, or pick the platform in the AI menu at the top right.

When the embedding platform changes, documents embedded with the old model are **re-indexed automatically in the
background**; the Documents tab shows "Updating document search…" meanwhile. **Re-index all** does it at once.

Alternative: put keys in environment variables (`GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`GROQ_API_KEY`). `ProviderSeeder` creates those providers on start-up.

### 2.7 Tests
In IntelliJ's terminal: `cd backend` → `./mvnw test` (Windows: `.\mvnw.cmd test`), or right-click
`backend/src/test/java` → **Run 'All Tests'**. The tests use in-memory H2 databases, the Demo provider and a small fake
AI server, so they need no database and no API key. They cover chat, RAG with sources, choosing documents, automatic
switching and resting platforms, the background re-index, the database-free status endpoint, key encryption and
masking, the app password, CORS and the sample data.

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

**Automatic switching when a platform hits its limit**
* Every AI error has a kind (`AiException.Kind`): `RATE_LIMIT` (HTTP 429), `UNAVAILABLE` (5xx, timeout, cannot
  connect), `AUTH` (401/403), `NOT_FOUND` (404) or `AI_ERROR`.
* `ProviderHealth` remembers, in memory, which platforms are **resting** after an error: a rate limit rests for the
  time the platform asks for (`Retry-After`, or Gemini's `retryDelay`, 60 s by default), a platform that does not
  respond for 30 s, a key or model error for 2 minutes. The AI menu shows it as a pink "resting 42 s" tag
  (`cooldownSeconds` in `GET /api/providers`).
* `ProviderService.fallbackChain` lists who may answer: the platform you chose first, then the other real platforms
  (a key or a URL, not resting), at most 3. A resting platform is skipped while a backup can answer (no slow call that
  will fail anyway), unless you picked it on purpose with **Ask &lt;platform&gt;**. The offline Demo is never used as a backup.
* `ChatService` tries them in order. The first that answers wins, and the answer gets a **notice**, e.g.
  *"Google Gemini hit its rate limit (HTTP 429), so Groq answered instead."*
* If all of them fail, **nothing is saved** (not even the question) and the API answers **502** with `code` and
  `provider` of the first failure. The front-end keeps the question and offers **Retry** and **Ask &lt;platform&gt;**,
  which sends the same text again with `providerId`; that platform is always tried first.
* Switch it off with *Switch automatically when a platform hits its limit* (AI menu or Settings): the request then
  carries `autoFallback: false` and only the chosen platform is tried.

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

ASK      question + chosen documents (none chosen = all) ──► load only those chunks
              ──► embed question ──► cosine similarity ──► top 4 chunks   (or keyword search, see below)
              ──► system prompt = RAG_PROMPT + "[1] (file) text … [2] …" ──► chat provider ──► answer with [1] [2]
```

* **Chunking**: `TextChunker.split` cuts at paragraph, sentence or space boundaries near 1000 characters, and the
  pieces overlap by 150 so a sentence on the border is still found.
* **Choosing documents (scoped search)**: the request says `useDocuments` and `documentIds`. `RetrievalService.search`
  loads only the chunks of those documents (`findByDocumentIdIn`), so a question about the café never pays for the
  other files. `useDocuments: false` skips the search completely: a plain chat with `CHAT_PROMPT`.
* **Vectors in a normal database**: a vector is stored as `byte[]` (float32), and `RetrievalService` computes cosine
  similarity in Java. This works the same on H2, SQL Server and PostgreSQL. (For millions of chunks you would use a
  vector database or Neon's `pgvector`; for a course project in-memory search is fast enough.)
* **Same model rule**: vectors from different embedding models cannot be compared. Each chunk stores the model it was
  embedded with (e.g. `GEMINI:gemini-embedding-001`). Vector search runs only when **every** chunk in the chosen
  documents has the current model; otherwise the search uses keywords (TF-IDF) over all chosen chunks, so no document
  is silently left out.
* **Keyword fallback**: no embedding provider, an outdated document, or an embedding service that is busy (e.g. a rate
  limit) → keyword search. In the last case the answer's notice says *"The embedding service is busy, so the document
  search used keywords."*
* **Automatic background re-index** (`EmbeddingMaintenance`): after start-up and after the embedding provider changes,
  documents embedded with another model are re-embedded on one background thread, so start-up and requests never
  wait for it. It stops at the first error (most likely a rate limit) and keeps the old vectors. `GET /api/status`
  says `reindexing: true` meanwhile, the Documents tab shows "Updating document search…", and **Re-index all** is
  refused (409) until it is done. Turn it off with `APP_AUTO_REINDEX=false`.
* **One merged prompt** (`ChatService.RAG_PROMPT`): when the passages answer the question, answer from them and cite
  each passage used as `[1]`, `[2]`; when they do not, say so in one short sentence, then answer from general
  knowledge and make clear that this part is not from the documents. Never invent facts and attribute them to the
  documents. The sources are saved with the answer and shown under it as cards.

### 3.3 REST API

| Method | URL | Purpose |
|--------|-----|---------|
| GET | `/api/status` | Health, active and embedding provider, `reindexing` (public, no password; never touches the database, so the keep-awake ping is cheap) |
| GET / POST | `/api/providers` | List (with `cooldownSeconds`, `lastError`) / add a platform |
| PUT / DELETE | `/api/providers/{id}` | Edit (empty key = keep) / delete |
| POST | `/api/providers/{id}/activate` | **Switch the chat platform** |
| POST | `/api/providers/{id}/test` | Send a test prompt |
| GET / POST | `/api/conversations` | List / create (body `{}`; `mode` is only kept for old chats) |
| GET / POST | `/api/conversations/{id}/messages` | History / send a question and get the answer (see below) |
| DELETE | `/api/conversations/{id}` | Delete a conversation |
| GET / POST | `/api/documents` | List (with `searchMode`) / upload a file (multipart `file`) |
| POST | `/api/documents/text` | Add pasted text |
| POST | `/api/documents/reindex` | Re-embed everything with the current embedding model now; **409** while the background re-index runs |
| DELETE | `/api/documents/{id}` | Delete a document |

**Send a question**: `POST /api/conversations/{id}/messages`

```jsonc
{
  "content": "Lệ phí phúc khảo là bao nhiêu?",  // required, at most 20,000 characters
  "useDocuments": true,     // false = plain chat; true = search documents
  "documentIds": [3],       // which documents; null or [] = all of them
  "providerId": 2,          // optional: answer with this platform first ("Ask Groq")
  "autoFallback": true      // optional, default true: if it fails, try the other platforms
}
```

Answer (200):

```jsonc
{
  "id": 42, "role": "assistant", "content": "Lệ phí phúc khảo là 50.000 đồng một môn [1]…",
  "provider": "Groq", "model": "qwen/qwen3.8-27b",          // the platform that really answered
  "sources": [{ "documentId": 3, "documentName": "quy-che-thi-mau.md", "chunkIndex": 2,
                "score": 0.812, "method": "vector", "snippet": "…" }],   // method: "vector" or "keyword"
  "createdAt": "2026-11-08T10:00:00",
  "notice": "Google Gemini hit its rate limit (HTTP 429), so Groq answered instead.",  // null = nothing special
  "durationMs": 2380,        // search + AI time
  "documentsSearched": 1     // null = documents were not used
}
```

The other new fields: `GET /api/providers` → `cooldownSeconds` (0 = healthy) and `lastError`;
`GET /api/documents` → `searchMode` = `vector` (current model), `outdated` (another model, re-indexed soon) or
`keyword` (no vectors); `GET /api/status` → `reindexing`.

Errors always come back as `{"error": "message"}` (`ApiExceptionHandler`). When every AI platform failed, the answer is
**502** `{"error": "Google Gemini: rate limit reached (HTTP 429) · Groq: …", "code": "RATE_LIMIT", "provider": "Google Gemini"}`:
`code` (`RATE_LIMIT`, `UNAVAILABLE`, `AUTH`, `NOT_FOUND` or `AI_ERROR`) and `provider` describe the first failure, the
platform you chose; `error` lists every platform that was tried. Nothing was saved, so the question can simply be sent
again.

### 3.4 Front-end

| File | Job |
|------|-----|
| `index.html` | The page: top bar (tabs, server status, AI menu, theme), the Chat / Documents / Settings tabs, password dialog, toast. Scripts load at the end, in this order |
| `css/style.css` | Design tokens (paper, ink, pen, highlighter colours) for light and dark, mapped onto Bootstrap's variables so its menus, dialogs and toasts match |
| `js/config.js` | Back-end URL: `localhost:8080` locally, the Render URL online |
| `js/api.js` | `fetch` wrapper: JSON, the `X-App-Password` header, time-outs, errors with `status`, `code` and `provider` |
| `js/ui.js` | Shared state, `esc()` for safe HTML, toast, password dialog, light / dark / auto theme |
| `js/messages.js` | Message bubbles: Markdown (`marked`, cleaned by `DOMPurify`), highlighter citation chips, source cards, thinking bubble with a seconds counter, error bubble with **Retry** / **Ask &lt;platform&gt;** |
| `js/chat.js` | Conversation list, document picker (saved in the browser), chat box, sending a question (the chat is created on its first question) |
| `js/documents.js` | Documents tab: drop zone, paste text, document cards with their search mode, re-index and its progress |
| `js/settings.js` | AI menu in the top bar, automatic switching, Settings tab (platform cards + the form with presets) |
| `js/app.js` | Start-up: wakes the free server (banner + retries), loads everything, keep-awake ping |

**Citations ("highlighter chips")**: the answer's Markdown is turned into HTML and cleaned by DOMPurify first. Then
`addCitationChips` walks the *text* of the cleaned page (never a search-and-replace on HTML, and never inside code) and
turns `[1]` or `[1, 2]` into buttons. Clicking one opens the sources and flashes the matching card.

**Fast on free hosting**
* Two web fonts only (Bricolage Grotesque 700 for titles, Be Vietnam Pro 400/600 for text, which handles Vietnamese
  well); model names and timings use the computer's own monospace font.
* Scripts load at the end of the page, so the page appears before the API answers, and the data fills in.
* Start-up: `GET /api/status` first (it wakes the server), then providers, conversations and documents **in parallel**.
  If the status takes more than 2.5 s, a banner explains that the free server is waking up (up to a minute); the app
  retries for about 2.5 minutes, then offers **Try again**.
* After a question the conversation list is updated in the page, not fetched again; opening Documents or Settings
  re-fetches at most once a minute.
* No polling, except: a keep-awake `GET /api/status` every 9 minutes while the tab is visible (so the server does not
  fall asleep during a demo), the re-index progress every 5 s (at most 3 minutes), and the wake-up retries.
* Animations only use transform, opacity or background-size, and they stop when the computer asks for reduced motion.

---

## 4. Configuration reference (environment variables)

| Variable | Local default | On Render |
|----------|---------------|-----------|
| `SPRING_PROFILES_ACTIVE` | `h2` in **AiChatApplication (H2, no setup)**; none = SQL Server | `prod` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | SQL Server `AiChatDB`, `sa`, `12345` (not used by `h2`) | Neon JDBC URL, user, password |
| `APP_PASSWORD` | empty = no password | **set it** |
| `APP_SECRET_KEY` | built-in dev value | **set a long random string, and never change it** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | `https://your-app.vercel.app` |
| `APP_SAMPLE_DATA` | `true` | `true` or `false` |
| `APP_AUTO_REINDEX` | `true` = re-index outdated documents in the background | `true` (`false` = only the **Re-index all** button does it) |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GROQ_API_KEY` | optional | optional |
| `PORT` | 8080 | set by Render |

### 4.1 Deploy in short
* **Neon**: create a free PostgreSQL project and copy its JDBC URL, user and password.
* **Render**: *New → Web Service* from the GitHub repo, **Root Directory `backend`**, Docker. Set
  `SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_PASSWORD`, `APP_SECRET_KEY`,
  `CORS_ALLOWED_ORIGINS` and the AI keys. The Dockerfile already uses JVM flags for the small free instance.
* **Vercel**: *New Project* from the same repo, **Root Directory `frontend`**, no build command. The Render URL is in
  `frontend/js/config.js`.
* Free plans sleep: Render after 15 minutes without visitors (the first request then takes up to a minute), Neon after
  a few idle minutes. The app explains the wait and keeps the server awake while its tab is open.

---

## 5. Troubleshooting

| Problem | Fix |
|---------|-----|
| The run configurations are missing (top right) | They live in `backend/.run/`. Open the **cloned folder** (with the root `pom.xml`), not a sub-folder, and wait for the Maven import. Quick fallback: open `AiChatApplication.java`, click ▶ next to `main`, then add `SPRING_PROFILES_ACTIVE=h2` under *Run → Edit Configurations → Environment variables*. |
| "No JDK" or red Java code | *File → Project Structure → SDK → Download JDK* → version 21 or newer. |
| `Port 8080 was already in use` | Another back-end is still running: stop it (red ■ in IntelliJ), then run again. |
| H2: start again with empty data | Stop the app and delete the `backend/data/` folder. |
| Front-end says *Cannot reach the server* | Is the back-end running? Is the URL in `js/config.js` right? Open the page through IntelliJ (`localhost:63342`), not by double-clicking it. |
| Banner *Waking up the free server…* | Normal on Render's free plan after 15 minutes without visitors: the first request takes up to a minute. If it gives up, click **Try again**. |
| Browser console: *blocked by CORS policy* | Add the front-end's exact origin to `CORS_ALLOWED_ORIGINS` (no trailing slash) and restart. Don't open the page as `file://`. |
| `HTTP 401 (check the API key)` / *rejected the API key* | Wrong or expired key: edit the provider, paste it again, then **Test**. |
| `HTTP 404 (check the model name…)` | The model name is wrong or retired: check the platform's model list and edit it. |
| `HTTP 429` (rate limit) | Free-tier limit. With **automatic switching** on (the default) the next platform answers by itself and the answer says so; the limited platform rests for a while (pink "resting … s" in the AI menu). Add a second platform (e.g. Groq) so there is one to switch to. With switching off, click **Ask &lt;platform&gt;** under the error, or wait a minute. |
| An answer has a pink note | Not an error: it says which platform answered instead, or that the document search used keywords. |
| *Re-index all* is greyed out, or says "already being updated" | A background re-index is running ("Updating document search…"). Wait until it finishes; the page notices by itself. |
| Answers ignore the documents | Check the document picker in the chat box (*No documents* = plain chat). On the Documents tab, **keyword** instead of **vector** means: tick *Use for embeddings* on Gemini/OpenAI/Ollama in Settings; outdated documents are then re-indexed automatically. |
| "Could not decrypt a saved API key" in the log | `APP_SECRET_KEY` changed: enter the keys again in Settings. |
| Ollama works locally but not on Render | Render cannot reach your PC. Use Ollama only with a local back-end. |
| SQL Server connection errors | Use **AiChatApplication (H2, no setup)** instead, or see the troubleshooting table in `assignment/docs/USER-GUIDE.md` (TCP/IP, port 1433, `sa` login). |
