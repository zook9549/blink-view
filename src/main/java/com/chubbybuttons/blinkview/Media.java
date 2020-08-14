package com.chubbybuttons.blinkview;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor
@Data
public class Media {
    @JsonIgnore
    private byte[] image;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd@h:mm a")
    private LocalDateTime captureTime;
    private String archivePath;
    private String sourceUrl;
    private String archiveUrl;
    private String camera;
    private String id;
    private String archiveId;
}
