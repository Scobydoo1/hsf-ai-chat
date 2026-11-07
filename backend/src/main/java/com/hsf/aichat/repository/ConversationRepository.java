package com.hsf.aichat.repository;

import com.hsf.aichat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();
}
