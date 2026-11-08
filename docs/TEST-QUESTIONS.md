# Test questions for the sample data

The app loads 4 sample documents on first start (Documents tab). They live in
`backend/src/main/resources/sample-data/`; you can also upload them by hand.

| File | What it is |
|------|------------|
| `greenleaf-cafe-handbook.md` | Fictional café: menu, prices, opening hours, loyalty program, staff rules (English) |
| `hsf-course-syllabus.md` | Sample course syllabus: schedule, grading, deadlines, AI-use policy (English) |
| `quy-che-thi-mau.md` | Sample exam regulations of a fictional university (Vietnamese) |
| `spring-boot-faq.md` | Real Spring Boot / JPA / RAG notes (English) |

There is one kind of chat. In the chat box, the **document picker** (left of *Auto-switch*) decides what each question
searches: keep it on **All documents**, or pick **Choose documents…** and tick the file you are testing, then ask the
questions below. With a real provider (e.g. Gemini) the answer should contain the expected fact and a yellow citation
chip like **1**; click it to see the passage. With the Demo provider you only see the best passage, which should come
from the expected file. The line under each answer shows the platform, the model, the time and how many documents were
searched.

## Green Leaf Café (`greenleaf-cafe-handbook.md`)

| Question | Expected answer |
|----------|-----------------|
| How much is a salted cream coffee, size L? | 52,000 VND |
| What time does the café close on Saturday? | 23:00 |
| How many loyalty points do I need for a free drink? | 100 points (1 point per 10,000 VND) |
| Can I pay with MoMo? Do you accept checks? | Yes, MoMo is accepted; checks are not |
| What is the starting hourly wage for part-time staff? | 25,000 VND/hour, 28,000 after 6 months |
| Quán có giao hàng miễn phí không? | Yes, for hotline orders over 300,000 VND (answer in Vietnamese) |

## Course syllabus (`hsf-course-syllabus.md`)

| Question | Expected answer |
|----------|-----------------|
| What do I need to pass the course? | Total ≥ 5.0/10 and final exam ≥ 4.0 |
| How much is the final exam worth? | 30% |
| Can I use ChatGPT in the final exam? | No (AI is allowed for labs/assignment/project, not the exam) |
| What happens if I submit the assignment 2 days late? | Minus 20% (10% per day, max 3 days) |
| Which week covers deployment? | Week 9 |

## Exam regulations, Vietnamese (`quy-che-thi-mau.md`)

| Question | Expected answer |
|----------|-----------------|
| Đến muộn bao nhiêu phút thì không được vào phòng thi? | Quá 15 phút |
| Lệ phí phúc khảo là bao nhiêu? | 50.000 đồng một môn, nộp trong 7 ngày |
| Dùng AI trong giờ thi bị xử lý thế nào? | Đình chỉ thi, bài thi 0 điểm |
| Điểm 7.5 quy đổi ra điểm chữ là gì? | B |
| When can I leave the exam room? (English question, Vietnamese doc) | After half of the exam time |

## Spring Boot FAQ (`spring-boot-faq.md`)

| Question | Expected answer |
|----------|-----------------|
| What is the difference between @Controller and @RestController? | View name vs response body (JSON) |
| Why do I get LazyInitializationException? | Lazy relation used after the session closed; use JOIN FETCH / @EntityGraph / DTO |
| What does ddl-auto=update do? | Creates missing tables/columns, never drops |
| What is RAG? | Retrieve relevant passages, add them to the prompt, then generate |

## Questions the documents do NOT answer (the AI should say so)

| Question | Expected behaviour |
|----------|--------------------|
| What is the café's Wi-Fi password this week? | The documents say it is printed on the receipt; it does not invent one |
| Who won the 2022 World Cup? | With documents on (**All documents**): one short sentence saying the documents do not cover it, then the answer from general knowledge, marked as not from the documents (Argentina). With **Don't use documents**: a normal answer, no citations |

## Choosing documents

1. Document picker → **Choose documents…** → tick only `spring-boot-faq.md`.
2. Ask *How much is a salted cream coffee, size L?* → the answer says the chosen documents do not contain it, and the
   line under it says *searched 1 doc*.
3. On the **Documents** tab click **Ask about this** on `greenleaf-cafe-handbook.md` → the chat box now searches only
   that file. Ask the same question again → 52,000 VND with a citation.
4. Pick **Don't use documents** → no sources and no *searched … docs* (the fastest option on the free plan).

## Switching platforms

1. Ask the same question with Gemini, then pick Groq or Claude in the AI menu (top right, **✦ Google Gemini ▾**) and
   ask again.
2. Each answer shows which provider and model wrote it under the bubble.
3. Note: switching the *chat* provider never requires re-indexing. Only changing the *embedding* provider does, and
   the app does it by itself in the background (Documents tab: "Updating document search…"; **Re-index all** does it
   at once).

## Automatic switching (needs two real providers, e.g. Gemini + Groq)

1. Check that *Switch automatically when a platform hits its limit* is on (AI menu or Settings) and that Gemini is the
   chat platform.
2. Make Gemini fail: the quickest way is **Edit** → change its chat model to a name that does not exist (e.g.
   `gemini-0-test`) → **Save**. (On a busy free tier a real HTTP 429 works the same way.)
3. Ask any question → **Groq** answers, and a pink note under the answer says why, e.g. *"Google Gemini could not find
   its model, so Groq answered instead."* The AI menu shows Gemini as *resting … s*.
4. Turn the switch **off** and ask again → an error bubble explains what failed, marks your question *Not sent*, and
   offers **Retry** and **Ask Groq**. Click **Ask Groq** → the same question is answered once (no duplicate).
5. Put Gemini's model name back and **Save**: saving ends its rest.
