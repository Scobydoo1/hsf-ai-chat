# Small Project – AI Chat App

Chat with an external AI, chat with your own documents (RAG), and switch AI platforms by configuring API keys.

| Part | Tech | Local | Cloud |
|------|------|-------|-------|
| `backend/` | Spring Boot 3.5, Java 21, Spring Data JPA, REST | IntelliJ → http://localhost:8080 | Render (Docker) |
| Database | JPA entities | SQL Server `AiChatDB` | Neon (PostgreSQL) |
| `frontend/` | HTML + Bootstrap 5 + plain JavaScript (no build) | any static server, e.g. port 5500 | Vercel |
| AI | Gemini, OpenAI-compatible (OpenAI, Groq, Ollama…), Anthropic Claude, offline Demo | | |

The full setup, code walkthrough and deploy steps are in **[docs/USER-GUIDE.md](docs/USER-GUIDE.md)**.

## Quick start

1. Run [backend/database/create-database.sql](backend/database/create-database.sql) once in SSMS.
2. IntelliJ → **File → Open → `small-project/backend`** → run **AiChatApplication (local SQL Server)**.
   Set `DB_PASSWORD` in the run configuration if your `sa` password is not `12345`.
3. Serve the front-end: in IntelliJ right-click `frontend/index.html` → **Open In → Browser**,
   or in a terminal `cd frontend` → `python -m http.server 5500` → open http://localhost:5500.
4. It works straight away with the **Demo** provider (no key). Add a real key on the **Settings** tab
   (a free Gemini key: https://aistudio.google.com/apikey).

Tests (no SQL Server, no API key needed): `cd backend` → `mvnw.cmd test`.
