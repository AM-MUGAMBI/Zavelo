package com.example.zavelo.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
           select m from Message m
           where (m.senderKey = :a and m.recipientKey = :b)
              or (m.senderKey = :b and m.recipientKey = :a)
           order by m.id asc
           """)
    List<Message> conversation(@Param("a") String a, @Param("b") String b);

    @Query("""
           select m from Message m
           where m.senderKey = :k or m.recipientKey = :k
           order by m.id desc
           """)
    List<Message> involving(@Param("k") String k);
}
