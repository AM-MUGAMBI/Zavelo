package com.example.zavelo.chat;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        @Index(columnList = "senderKey"),
        @Index(columnList = "recipientKey")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 9)
    private String senderKey;

    @Column(nullable = false, length = 9)
    private String recipientKey;

    /** The text, or the caption of a file. */
    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    /** "TEXT", "AUDIO" (voice note) or "FILE". Null (older rows) means text. */
    @Column(length = 10)
    private String kind;

    @Column(length = 60)
    private String audioFile;

    @Column(length = 30)
    private String audioMime;

    private Integer durationSec;

    @Column(length = 200)
    private String fileName;

    @Column(length = 100)
    private String fileMime;

    private Long fileSize;

    @Column(length = 60)
    private String fileStored;

    protected Message() {}

    public Message(String senderKey, String recipientKey, String body) {
        this.senderKey = senderKey;
        this.recipientKey = recipientKey;
        this.body = body;
        this.kind = "TEXT";
    }

    public static Message voice(String senderKey, String recipientKey, String file, String mime, int seconds) {
        Message m = new Message(senderKey, recipientKey, "");
        m.kind = "AUDIO";
        m.audioFile = file;
        m.audioMime = mime;
        m.durationSec = seconds;
        return m;
    }

    public static Message file(String senderKey, String recipientKey, String caption,
                               String name, String mime, long size, String stored) {
        Message m = new Message(senderKey, recipientKey, caption);
        m.kind = "FILE";
        m.fileName = name;
        m.fileMime = mime;
        m.fileSize = size;
        m.fileStored = stored;
        return m;
    }

    public boolean isAudio() { return "AUDIO".equals(kind); }
    public boolean isFile() { return "FILE".equals(kind); }

    public Long getId() { return id; }
    public String getSenderKey() { return senderKey; }
    public String getRecipientKey() { return recipientKey; }
    public String getBody() { return body; }
    public Instant getSentAt() { return sentAt; }
    public String getKind() { return kind; }
    public String getAudioFile() { return audioFile; }
    public String getAudioMime() { return audioMime; }
    public Integer getDurationSec() { return durationSec; }
    public String getFileName() { return fileName; }
    public String getFileMime() { return fileMime; }
    public Long getFileSize() { return fileSize; }
    public String getFileStored() { return fileStored; }
}
