package com.myorg;

import software.amazon.awscdk.*;
import software.constructs.Construct;

import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.amazon.awscdk.services.dynamodb.*;

import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;

import software.amazon.awscdk.services.stepfunctions.*;

import software.amazon.awscdk.services.mediaconvert.CfnJobTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class InfrastructureStack extends Stack {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        /* ───────────────────────── buckets ───────────────────────── */
        Bucket sourceBucket = Bucket.Builder.create(this, "AE2SourceBucket")
            .versioned(true)
            .lifecycleRules(List.of(
                LifecycleRule.builder()
                    .enabled(true)
                    .expiration(Duration.days(90))
                    .build()))
            .build();

        Bucket outputBucket = Bucket.Builder.create(this, "AE2OutputBucket")
            .versioned(false)
            .cors(List.of(
                CorsRule.builder()
                    .allowedMethods(List.of(HttpMethods.GET))
                    .allowedOrigins(List.of("*"))
                    .build()))
            .build();

        /* ─────────────────────── DynamoDB table ──────────────────── */
        Table jobTable = Table.Builder.create(this, "AE2JobTable")
            .partitionKey(Attribute.builder()
                .name("jobId")
                .type(AttributeType.STRING)
                .build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        jobTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
            .indexName("status-index")
            .partitionKey(Attribute.builder()
                .name("jobStatus")
                .type(AttributeType.STRING)
                .build())
            .projectionType(ProjectionType.ALL)
            .build());

        /* ─────────────── Step Functions stub machine ─────────────── */
        Pass noOp = Pass.Builder.create(this, "NoOp").build();

        StateMachine sm = StateMachine.Builder.create(this, "AE2TranscodeStateMachine")
            .definitionBody(DefinitionBody.fromChainable(noOp))
            .timeout(Duration.hours(2))
            .build();

        /* ─────────────── Lambda: job submitter  ─────────────── */
        Function jobSubmitFn = Function.Builder.create(this, "JobSubmitFn")
            .functionName("JobSubmitFn")
            .runtime(Runtime.JAVA_21)                     // no more ambiguity
            .handler("com.autoencode.backend.JobSubmitHandler::handleRequest")
            .code(Code.fromAsset("../backend-jobs/target/backend-jobs-1.0-SNAPSHOT.jar"))
            .memorySize(2048)
            .environment(Map.of(
                "TABLE_NAME", jobTable.getTableName(),
                "STATE_MACHINE_ARN", sm.getStateMachineArn()))
            .build();

        jobTable.grantReadWriteData(jobSubmitFn);
        sm.grantStartExecution(jobSubmitFn);

        sourceBucket.addEventNotification(EventType.OBJECT_CREATED_PUT,
            new LambdaDestination(jobSubmitFn),
            NotificationKeyFilter.builder().prefix("source/").build());

        /* ─────────── MediaConvert job templates (3) ─────────── */
        createJobTemplate("JobTemplate1080",
                "AutoEncode2-1080p-h265",
                "../templates/1080p-h265.json");

        createJobTemplate("JobTemplate720",
                "AutoEncode2-720p-h264",
                "../templates/720p-h264.json");

        createJobTemplate("JobTemplate540",
                "AutoEncode2-540p-h264-qvbr",
                "../templates/540p-h264-qvbr.json");
    }

    /** Reads JSON file ➜ Map ➜ CfnJobTemplate */
    private void createJobTemplate(String id, String name, String filePath) {

        Map<String, Object> root;
        try {
                root = MAPPER.readValue(
                        Files.readString(Path.of(filePath)),
                        new TypeReference<>() {});
        } catch (Exception e) {
                throw new RuntimeException("Unable to read/parse " + filePath, e);
        }

        Object settingsObj = root.containsKey("Settings") ? root.get("Settings") : root;

        CfnJobTemplate.Builder.create(this, id)
                .name(name)
                .settingsJson(settingsObj)
                .build();
        }
}
