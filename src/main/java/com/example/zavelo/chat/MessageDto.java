package com.example.zavelo.chat;

import java.time.Instant;

/** What the browser sees: keys only, never usernames or stored file names. type is "text", "audio" or "file". */
public record MessageDto(Long id, String from, String to, String type, String body, Integer duration,
                         Instant sentAt, String fileName, String fileMime, Long fileSize) {

    public static MessageDto of(Message m) {
        String type = m.isAudio() ? "audio" : m.isFile() ? "file" : "text";
        return new MessageDto(m.getId(), m.getSenderKey(), m.getRecipientKey(), type, m.getBody(),
                m.getDurationSec(), m.getSentAt(), m.getFileName(), m.getFileMime(), m.getFileSize());
    }
}
