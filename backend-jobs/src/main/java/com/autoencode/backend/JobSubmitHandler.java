package com.autoencode.backend;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class JobSubmitHandler implements RequestHandler<S3Event, Void> {

    private static final String TABLE_NAME        = System.getenv("TABLE_NAME");
    private static final String STATE_MACHINE_ARN = System.getenv("STATE_MACHINE_ARN");
    private static final String SOURCE_BUCKET     = System.getenv("SOURCE_BUCKET");

    private final DynamoDbClient ddb = DynamoDbClient.create();
    private final SfnClient      sfn = SfnClient.create();

    @Override
    public Void handleRequest(S3Event event, Context context) {

        event.getRecords().forEach(rec -> {

            /* ─────────── grab basic keys ─────────── */
            String key   = rec.getS3().getObject().getKey();   // example: source/video.mp4
            String jobId = UUID.randomUUID().toString();

            /* ─────────── 1 / 3  → DynamoDB record ─────────── */
            ddb.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(Map.of(
                            "jobId",      AttributeValue.builder().s(jobId).build(),
                            "sourceKey",  AttributeValue.builder().s(key).build(),
                            "jobStatus",  AttributeValue.builder().s("PENDING").build(),
                            "createdAt",  AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build());

            /* ─────────── 2 / 3  → Step-Functions input payload ─────────── */
            String inputJson = """
            {
              "jobId":"%s",
              "sourceKey":"%s",
              "sourceBucket":"%s",
              "renditions":[
                {"templateName":"AutoEncode2-1080p-h265","outputPrefix":"1080","threshold":93},
                {"templateName":"AutoEncode2-720p-h264" , "outputPrefix":"720" ,"threshold":92},
                {"templateName":"AutoEncode2-540p-h264-qvbr","outputPrefix":"540","threshold":90}
              ]
            }
            """.formatted(jobId, key, SOURCE_BUCKET);

            /* ─────────── 3 / 3  → kick off execution ─────────── */
            sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(STATE_MACHINE_ARN)
                    .name("job-" + jobId)
                    .input(inputJson)
                    .build());
        });
        return null;
    }
}
