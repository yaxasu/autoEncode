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

    private final DynamoDbClient ddb = DynamoDbClient.create();
    private final SfnClient      sfn = SfnClient.create();

    @Override
    public Void handleRequest(S3Event event, Context ctx) {
        event.getRecords().forEach(rec -> {

            String key       = rec.getS3().getObject().getKey();     // e.g. source/video.mp4
            String srcBucket = rec.getS3().getBucket().getName();
            if (!key.startsWith("source/")) return;   // defense-in-depth

            String jobId = UUID.randomUUID().toString();

            /* 1 / 3  → DynamoDB record */
            ddb.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(Map.of(
                            "jobId",      AttributeValue.builder().s(jobId).build(),
                            "sourceKey",  AttributeValue.builder().s(key).build(),
                            "jobStatus",  AttributeValue.builder().s("PENDING").build(),
                            "createdAt",  AttributeValue.builder().s(Instant.now().toString()).build()))
                    .build());

            /* 2 / 3  → Step-Functions input payload */
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
            """.formatted(jobId, key, srcBucket);

            /* 3 / 3  → kick off execution */
            sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(STATE_MACHINE_ARN)
                    .name("job-" + jobId)
                    .input(inputJson)
                    .build());
        });
        return null;
    }
}
