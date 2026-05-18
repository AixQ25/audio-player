package com.aixq.movieaudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SubtitleParser {
    private SubtitleParser() {
    }

    public static ArrayList<SubtitleCue> parse(InputStream inputStream) throws IOException {
        String text = readText(inputStream)
            .replace("\uFEFF", "")
            .replace("\u0000", "")
            .replace("\r", "")
            .replaceFirst("(?i)^WEBVTT[^\\n]*\\n", "")
            .trim();

        ArrayList<SubtitleCue> cues = new ArrayList<>();
        if (text.isEmpty()) {
            return cues;
        }

        String[] blocks = text.split("\\n\\s*\\n");
        for (String block : blocks) {
            SubtitleCue cue = parseBlock(block);
            if (cue != null) {
                cues.add(cue);
            }
        }

        if (cues.isEmpty()) {
            cues.addAll(parseAss(text));
        }

        Collections.sort(cues, new Comparator<SubtitleCue>() {
            @Override
            public int compare(SubtitleCue left, SubtitleCue right) {
                return Long.compare(left.startMs, right.startMs);
            }
        });
        return cues;
    }

    public static SubtitleCue findCurrent(List<SubtitleCue> cues, long positionMs) {
        for (SubtitleCue cue : cues) {
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                return cue;
            }
        }
        return null;
    }

    public static int findCurrentIndex(List<SubtitleCue> cues, long positionMs) {
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                return i;
            }
        }
        return -1;
    }

    private static SubtitleCue parseBlock(String block) {
        String[] rawLines = block.split("\\n");
        ArrayList<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            String upper = line.toUpperCase(Locale.ROOT);
            if (!line.isEmpty() && !upper.startsWith("NOTE") && !upper.startsWith("STYLE") && !upper.startsWith("REGION")) {
                lines.add(line);
            }
        }

        int timeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("-->")) {
                timeIndex = i;
                break;
            }
        }
        if (timeIndex < 0) {
            return null;
        }

        String[] times = lines.get(timeIndex).split("-->");
        if (times.length != 2) {
            return null;
        }

        long startMs = parseTimecode(times[0].trim().split("\\s+")[0]);
        long endMs = parseTimecode(times[1].trim().split("\\s+")[0]);
        if (startMs < 0L || endMs <= startMs) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = timeIndex + 1; i < lines.size(); i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(lines.get(i).replaceAll("<[^>]+>", "").trim());
        }

        String cueText = builder.toString().trim();
        if (cueText.isEmpty()) {
            return null;
        }
        return new SubtitleCue(startMs, endMs, cueText);
    }

    private static ArrayList<SubtitleCue> parseAss(String text) {
        ArrayList<SubtitleCue> cues = new ArrayList<>();
        String[] lines = text.split("\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("dialogue:")) {
                continue;
            }

            String[] fields = splitAssDialogue(line.substring(line.indexOf(':') + 1));
            if (fields == null || fields.length < 10) {
                continue;
            }

            long startMs = parseTimecode(fields[1].trim());
            long endMs = parseTimecode(fields[2].trim());
            String cueText = fields[9]
                .replace("\\N", " ")
                .replace("\\n", " ")
                .replaceAll("\\{[^}]*\\}", "")
                .replaceAll("<[^>]+>", "")
                .trim();

            if (startMs >= 0L && endMs > startMs && !cueText.isEmpty()) {
                cues.add(new SubtitleCue(startMs, endMs, cueText));
            }
        }
        return cues;
    }

    private static String[] splitAssDialogue(String value) {
        String[] fields = new String[10];
        int field = 0;
        int start = 0;
        for (int i = 0; i < value.length() && field < 9; i++) {
            if (value.charAt(i) == ',') {
                fields[field] = value.substring(start, i);
                field++;
                start = i + 1;
            }
        }
        if (field < 9) {
            return null;
        }
        fields[9] = value.substring(start);
        return fields;
    }

    private static long parseTimecode(String value) {
        String cleaned = value.replace(',', '.').trim();
        String[] parts = cleaned.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return -1L;
        }

        try {
            double seconds = Double.parseDouble(parts[parts.length - 1]);
            long minutes = Long.parseLong(parts[parts.length - 2]);
            long hours = parts.length == 3 ? Long.parseLong(parts[0]) : 0L;
            return Math.round(((hours * 3600L) + (minutes * 60L) + seconds) * 1000.0);
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    private static String readText(InputStream inputStream) throws IOException {
        byte[] bytes = readBytes(inputStream);
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFE
            && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        Charset utf16 = guessUtf16(bytes);
        if (utf16 != null) {
            return new String(bytes, utf16);
        }

        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null && looksLikeSubtitle(utf8)) {
            return utf8;
        }

        Charset gb18030 = Charset.forName("GB18030");
        String gbText = new String(bytes, gb18030);
        if (looksLikeSubtitle(gbText)) {
            return gbText;
        }

        return utf8 != null ? utf8 : gbText;
    }

    private static byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            return null;
        }
    }

    private static Charset guessUtf16(byte[] bytes) {
        int sampleSize = Math.min(bytes.length, 400);
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i < sampleSize; i++) {
            if (bytes[i] == 0) {
                if ((i & 1) == 0) {
                    evenZeros++;
                } else {
                    oddZeros++;
                }
            }
        }

        if (oddZeros > sampleSize / 5 && oddZeros > evenZeros * 2) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenZeros > sampleSize / 5 && evenZeros > oddZeros * 2) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private static boolean looksLikeSubtitle(String text) {
        String normalized = text.replace("\u0000", "").toLowerCase(Locale.ROOT);
        return normalized.contains("-->") || normalized.contains("dialogue:");
    }
}
