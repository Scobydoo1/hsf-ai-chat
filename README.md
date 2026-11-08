# Small Project – AI Chat App

One chat with a document picker: ask the AI anything, or choose which of your documents it should answer from
(RAG, with citations). Switch AI platforms by adding API keys – and when Gemini or Groq hits its free-tier limit,
the app switches to another platform automatically and tells you.

| Part | Tech | Local | Cloud |
|------|------|-------|-------|
| `backend/` | Spring Boot 3.5, Java 21, Spring Data JPA, REST | IntelliJ → http://localhost:8080 | Render (Docker) |
| Database | JPA entities | H2 file (no setup) or SQL Server `AiChatDB` | Neon (PostgreSQL) |
| `frontend/` | HTML + Bootstrap 5 + plain JavaScript (no build) | IntelliJ's built-in browser (port 63342) | Vercel |
| AI | Gemini, OpenAI-compatible (OpenAI, Groq, Ollama…), Anthropic Claude, offline Demo | | |

The full setup, code walkthrough and deploy steps are in **[docs/USER-GUIDE.md](docs/USER-GUIDE.md)**.

## Run it on any computer (IntelliJ)

You need **JDK 21 or newer** (IntelliJ can download one: *File → Project Structure → SDK → Download JDK*),
**IntelliJ IDEA** Community or Ultimate, and a browser. Option A needs nothing else: no Maven, Node or SQL Server.

1. Clone https://github.com/Scobydoo1/hsf-ai-chat, then in IntelliJ **File → Open →** the cloned folder (the one
   with `pom.xml`). IntelliJ imports the Maven project by itself (the first time it downloads the libraries).
2. Start the back-end with one of the run configurations (top right):
   - **A. AiChatApplication (H2, no setup)** – recommended. The data is kept in `backend/data/` (delete it to start
     fresh); tables at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/aichat`, user `sa`, no password).
   - **B. AiChatApplication (local SQL Server)** – run [backend/database/create-database.sql](backend/database/create-database.sql)
     once in SSMS; set `DB_PASSWORD` in the run configuration if your `sa` password is not `123`.

   It is ready when the log says `Started AiChatApplication`: http://localhost:8080/api/status shows `"status":"ok"`.
3. Open `frontend/index.html` in IntelliJ and click a browser icon in the top-right corner of the editor (or right-click
   the file → **Open In → Browser**). It opens `http://localhost:63342/...`; do not double-click the file instead,
   a `file://` page cannot call the API.
4. It works straight away with the offline **Demo** provider. Add a real key on the **Settings** tab
   (a free Gemini key: https://aistudio.google.com/apikey).

Tests (no database, no API key): in IntelliJ's terminal `cd backend`, then `./mvnw test` (Windows: `.\mvnw.cmd test`).
