# Test questions for the sample data

The app loads 4 sample documents on first start (Documents tab). They live in
`backend/src/main/resources/sample-data/`; you can also upload them by hand.

| File | What it is |
|------|------------|
| `greenleaf-cafe-handbook.md` | Fictional café: menu, prices, opening hours, loyalty program, staff rules (English) |
| `hsf-course-syllabus.md` | Sample course syllabus: schedule, grading, deadlines, AI-use policy (English) |
| `quy-che-thi-mau.md` | Sample exam regulations of a fictional university (Vietnamese) |
| `spring-boot-faq.md` | Real Spring Boot / JPA / RAG notes (English) |

Use **New chat → Chat with my documents (RAG)** and ask the questions below.
With a real provider (e.g. Gemini) the answer should contain the expected fact and a citation like [1].
With the Demo provider you only see the best passage, which should come from the expected file.

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
| Who won the 2022 World Cup? | In RAG mode: "the documents do not contain this". In normal chat mode: a normal answer |

## Switching platforms

1. Ask the same question with Gemini, then switch the **AI:** box (top right) to Groq or Claude and ask again.
2. Each answer shows which provider and model wrote it under the bubble.
3. Note: switching the *chat* provider never requires re-indexing. Only changing the *embedding* provider does
   (Documents tab → **Re-index all**).
