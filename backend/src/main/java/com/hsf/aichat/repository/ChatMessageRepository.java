package com.hsf.aichat.repository;

import com.hsf.aichat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Only the newest 9 messages, newest first: the history sent to the AI (see ChatService.send) */
    List<ChatMessage> findTop9ByConversationIdOrderByIdDesc(Long conversationId);

    @Transactional
    @Modifying
    @Query("delete from ChatMessage m where m.conversation.id = :conversationId")
    void deleteByConversationId(Long conversationId);
}
