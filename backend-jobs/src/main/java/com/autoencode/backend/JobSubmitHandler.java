package com.autoencode.backend;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class JobSubmitHandler implements RequestHandler<S3Event, Void> {

    private final String tableName = System.getenv("TABLE_NAME");
    private final String stateMachineArn = System.getenv("STATE_MACHINE_ARN");

    private final DynamoDbClient ddb = DynamoDbClient.create();
    private final SfnClient sfn = SfnClient.create();

    @Override
    public Void handleRequest(S3Event event, Context context) {
        event.getRecords().forEach(rec -> {
            String bucket = rec.getS3().getBucket().getName();
            String key = rec.getS3().getObject().getKey();
            String jobId = UUID.randomUUID().toString();

            // 1. Put item into DynamoDB
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "jobId", AttributeValue.builder().s(jobId).build(),
                            "sourceKey", AttributeValue.builder().s(key).build(),
                            "jobStatus", AttributeValue.builder().s("PENDING").build(),
                            "createdAt", AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build());

            // 2. Kick off Step Functions execution (payload is minimal for now)
            sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name("job-" + jobId)
                    .input("{\"jobId\":\"" + jobId + "\", \"sourceKey\":\"" + key + "\"}")
                    .build());
        });
        return null;
    }
}