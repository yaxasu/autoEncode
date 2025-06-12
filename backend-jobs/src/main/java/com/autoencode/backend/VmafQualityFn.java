/* ────────────────────────────────────────────────────────────────
 *  VmafQualityFn.java  —  COMPLETE, REVISED
 * ──────────────────────────────────────────────────────────────── */
package com.autoencode.backend;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VmafQualityFn implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TMP        = "/tmp";
    private static final String FFMPEG     = "/opt/bin/ffmpeg";
    private static final String VMAF_MODEL = "/opt/share/model/vmaf_v0.6.1.json";
    private static final String TABLE      = System.getenv("TABLE_NAME");

    private static final S3Client     S3        = S3Client.create();
    private static final S3Presigner  PRESIGNER = S3Presigner.create();
    private static final DynamoDbClient DDB     = DynamoDbClient.create();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context ctx) {
        var log = ctx.getLogger();

        String jobId        = (String)  input.get("jobId");
        String sourceBucket = (String)  input.get("sourceBucket");
        String sourceKey    = (String)  input.get("sourceKey");
        String renditionUri = (String)  input.get("renditionUri");
        int    threshold    = ((Number) input.get("threshold")).intValue();

        if (jobId == null || sourceBucket == null || sourceKey == null || renditionUri == null) {
            throw new IllegalArgumentException("Missing keys in payload: " + input);
        }

        Path src  = scratchPath("src",  ".mp4");
        Path rend = scratchPath("rend", ".mp4");
        Path json = scratchPath("vmaf",".json");

        try {
            log.log("Downloading source + rendition …");
            download(sourceBucket, sourceKey, src);

            S3Path rPath = S3Path.parse(renditionUri);
            download(rPath.bucket(), rPath.key(), rend);

            // If a playlist (.m3u8) pre-sign its media segments
            if (renditionUri.endsWith(".m3u8")) {
                rend = presignPlaylist(rend, rPath, ctx);
            }

            runFfmpeg(src, rend, json, ctx);

            double score = parseVmaf(json);
            boolean pass = score >= threshold;

            recordScore(jobId, score, pass);

            return Map.of(
                    "jobId",  jobId,
                    "score",  score,
                    "passed", pass
            );
        } finally {
            deleteQuietly(src, rend, json);
        }
    }

    /* ───────────────────────── helpers ───────────────────────── */

    private record S3Path(String bucket, String key) {
        private static final Pattern RE = Pattern.compile("^s3://([^/]+)/(.+)$");

        static S3Path parse(String uri) {
            Matcher m = RE.matcher(uri);
            if (!m.matches()) throw new IllegalArgumentException("Bad S3 URI: " + uri);
            return new S3Path(m.group(1), m.group(2));
        }
    }

    private static Path scratchPath(String prefix, String ext) {
        return Path.of(TMP, prefix + "-" + UUID.randomUUID() + ext);
    }

    private static void download(String bucket, String key, Path dest) {
        try {
            Files.createDirectories(dest.getParent());
            Files.deleteIfExists(dest);
        } catch (IOException e) {
            throw new RuntimeException("Preparing " + dest, e);
        }

        S3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                ResponseTransformer.toFile(dest));
    }

    /**
     * Pre-sign the media playlist (child .m3u8) and all .ts segments, returning a writable
     * copy that FFmpeg can consume without credentials.
     */
    private static Path presignPlaylist(Path playlist, S3Path master, Context ctx) {
        try {
            List<String> lines = Files.readAllLines(playlist);

            // search for first media playlist
            for (String ln : lines) {
                if (!ln.startsWith("#") && ln.trim().endsWith(".m3u8")) {
                    String mediaName = ln.trim();
                    String mediaKey  = master.key()
                                             .substring(0, master.key().lastIndexOf('/') + 1) + mediaName;

                    Path mediaLocal = scratchPath("media", ".m3u8");
                    download(master.bucket(), mediaKey, mediaLocal);

                    return rewriteMediaPlaylist(mediaLocal,
                            new S3Path(master.bucket(), mediaKey), ctx);
                }
            }
            // master == media
            return rewriteMediaPlaylist(playlist, master, ctx);

        } catch (IOException e) {
            throw new RuntimeException("Process .m3u8 playlist", e);
        }
    }

    private static Path rewriteMediaPlaylist(Path playlist, S3Path origin, Context ctx) {
        try {
            List<String> lines = Files.readAllLines(playlist);
            String baseKey = origin.key().substring(0, origin.key().lastIndexOf('/') + 1);

            Path rewritten = scratchPath("signed", ".m3u8");
            try (BufferedWriter out = Files.newBufferedWriter(rewritten)) {

                for (String ln : lines) {
                    String trimmed = ln.trim();

                    boolean isMedia = !trimmed.isEmpty()
                                    && !trimmed.startsWith("#")
                                    && !trimmed.startsWith("http");

                    if (isMedia) {
                        String segKey = baseKey + trimmed;

                        GetObjectRequest getReq = GetObjectRequest.builder()
                                .bucket(origin.bucket())
                                .key(segKey)
                                .build();

                        String url = PRESIGNER.presignGetObject(
                                        GetObjectPresignRequest.builder()
                                                .signatureDuration(Duration.ofHours(6))
                                                .getObjectRequest(getReq)
                                                .build())
                                .url()
                                .toString();

                        out.write(url);
                    } else {
                        out.write(ln);
                    }
                    out.newLine();
                }
            }
            return rewritten;

        } catch (IOException ioe) {
            throw new RuntimeException("Rewrite media playlist", ioe);
        }
    }

    private static void runFfmpeg(Path src, Path rend, Path jsonOut, Context ctx) {
        var log = ctx.getLogger();
        ProcessBuilder pb = new ProcessBuilder(
                FFMPEG, "-hide_banner",
                "-i", src.toString(),
                "-i", rend.toString(),
                "-lavfi", "libvmaf=log_fmt=json:log_path=" + jsonOut + ":model_path=" + VMAF_MODEL,
                "-f", "null", "-")
                .redirectErrorStream(true);

        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.log("[ffmpeg] " + line);
                }
            }

            boolean finished = p.waitFor(Duration.ofMinutes(7).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("ffmpeg timed out (7 min)");
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("ffmpeg exited " + p.exitValue());
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for ffmpeg", ie);

        } catch (IOException ioe) {
            throw new RuntimeException("Unable to start ffmpeg", ioe);
        }
    }

    private static double parseVmaf(Path jsonFile) {
        try {
            JsonNode root = JSON.readTree(jsonFile.toFile());

            JsonNode mean = root.path("pooled_metrics").path("vmaf").path("mean");
            if (mean.isNumber()) return mean.asDouble();

            JsonNode fallback = root.path("frames").path(0).path("metrics").path("vmaf");
            if (fallback.isNumber()) return fallback.asDouble();

            throw new IllegalStateException("VMAF score not found in JSON: " + root);

        } catch (IOException e) {
            throw new RuntimeException("Parse VMAF JSON", e);
        }
    }

    private static void recordScore(String jobId, double score, boolean passed) {
        DDB.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("jobId",
                        AttributeValue.builder().s(jobId).build()))
                .updateExpression("SET renditionStatus = :s, vmafScore = :v")
                .expressionAttributeValues(Map.of(
                        ":s", AttributeValue.builder()
                                .s(passed ? "PASSED" : "FAILED_QUALITY").build(),
                        ":v", AttributeValue.builder()
                                .n(BigDecimal.valueOf(score)
                                        .setScale(2, RoundingMode.HALF_UP)
                                        .toPlainString()).build()))
                .build());
    }

    private static void deleteQuietly(Path... paths) {
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }
}
